/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

/**
 * $Id: $
 */

package com.linkedin.r2.transport.http.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.common.callback.Callback;
import com.linkedin.common.util.None;
import com.linkedin.r2.util.Cancellable;
import com.linkedin.r2.util.LinkedDeque;

/**
 * @author Steven Ihde
 * @version $Revision: $
 */

public class AsyncPoolImpl<T> implements AsyncPool<T>
{
  private static final Logger LOG = LoggerFactory.getLogger(AsyncPoolImpl.class);

  // Configured
  private final String _poolName;
  private final Lifecycle<T> _lifecycle;
  private final int _maxSize;
  private final long _idleTimeout;
  private final ScheduledExecutorService _timeoutExecutor;
  private volatile ScheduledFuture<?> _objectTimeoutFuture;

  private enum State { NOT_YET_STARTED, RUNNING, SHUTTING_DOWN, STOPPED }

  // All members below are protected by this lock
  // Never call user code (callbacks) while holding this lock
  private final Object _lock = new Object();
  // Including idle, checked out, and creations/destructions in progress
  private int _poolSize = 0;
  // Unused objects live here
  private final Deque<TimedObject<T>> _idle = new LinkedList<TimedObject<T>>();
  // When no unused objects are available, callbacks live here while they wait
  // for a new object (either returned by another user, or newly created)
  private final LinkedDeque<Callback<T>> _waiters = new LinkedDeque<Callback<T>>();
  private Throwable _lastCreateError = null;
  private State _state = State.NOT_YET_STARTED;
  private Callback<None> _shutdownCallback = null;

  // Statistics only
  private int _totalCreated;
  private int _totalDestroyed;
  private int _createErrors;
  private int _destroyErrors;


  public AsyncPoolImpl(String name,
                       Lifecycle<T> lifecycle,
                       int maxSize,
                       long idleTimeout,
                       ScheduledExecutorService timeoutExecutor)
  {
    _poolName = name;
    _lifecycle = lifecycle;
    _maxSize = maxSize;
    _idleTimeout = idleTimeout;
    _timeoutExecutor = timeoutExecutor;
  }

  @Override
  public void start()
  {
    synchronized (_lock)
    {
      if (_state != State.NOT_YET_STARTED)
      {
        throw new IllegalStateException(_poolName + " is " + _state);
      }
      _state = State.RUNNING;
      if (_idleTimeout > 0)
      {
        long freq = Math.min(_idleTimeout / 10, 1000);
        _objectTimeoutFuture = _timeoutExecutor.scheduleAtFixedRate(new Runnable() {
          @Override
          public void run()
          {
            timeoutObjects();
          }
        }, freq, freq, TimeUnit.MILLISECONDS);
      }
    }
  }

  @Override
  public void shutdown(Callback<None> callback)
  {
    final State state;
    synchronized (_lock)
    {
      state = _state;
      if (state == State.RUNNING)
      {
        _state = State.SHUTTING_DOWN;
        _shutdownCallback = callback;
      }
    }
    if (state != State.RUNNING)
    {
      // Retest state outside the sychronized block, since we don't want to invoke this
      // callback inside a synchronized block
      callback.onError(new IllegalStateException(_poolName + " is " + _state));
      return;
    }
    LOG.info("{}: {}", _poolName, "shutdown requested");
    shutdownIfNeeded();
  }

  @Override
  public Collection<Callback<T>> cancelWaiters()
  {
    synchronized (_lock)
    {
      List<Callback<T>> cancelled = new ArrayList<Callback<T>>(_waiters.size());
      for (Callback<T> item; (item = _waiters.poll()) != null;)
      {
         cancelled.add(item);
      }
      return cancelled;
    }
  }

  @Override
  public Cancellable get(final Callback<T> callback)
  {
    // getter needs to add to wait queue atomically with check for empty pool
    // putter needs to add to pool atomically with check for empty wait queue
    boolean create;
    final LinkedDeque.Node<Callback<T>> node;
    for (;;)
    {
      TimedObject<T> obj = null;
      final State state;
      synchronized (_lock)
      {
        state = _state;
        if (state == State.RUNNING)
        {
          obj = _idle.pollLast();
          if (obj == null)
          {
            // No objects available; add to waiter list and break out of loop
            node = _waiters.addLastNode(callback);
            create = shouldCreate();
            break;
          }
        }
      }
      if (state != State.RUNNING)
      {
        // Defer execution of the callback until we are out of the synchronized block
        callback.onError(new IllegalStateException(_poolName + " is " + _state));
        return null;
      }
      T rawObj = obj.get();
      if (_lifecycle.validateGet(rawObj))
      {
        trc("dequeued an idle object");
        // Valid object; done
        callback.onSuccess(rawObj);
        return null;
      }
      // Invalid object, discard it and keep trying
      destroy(rawObj, true);
      trc("dequeued and disposed an invalid idle object");
    }
    trc("enqueued a waiter");
    if (create)
    {
      create();
    }
    return new Cancellable()
    {
      @Override
      public boolean cancel()
      {
        synchronized (_lock)
        {
          return _waiters.removeNode(node) != null;
        }
      }
    };
  }

  @Override
  public void put(T obj)
  {
    if (!_lifecycle.validatePut(obj))
    {
      destroy(obj, true);
      return;
    }
    add(obj);
  }

  private void add(T obj)
  {
    final Callback<None> shutdown;
    Callback<T> waiter;
    synchronized (_lock)
    {
      waiter = _waiters.poll();
      if (waiter == null)
      {
        _idle.offerLast(new TimedObject<T>(obj));
      }
      shutdown = checkShutdownComplete();
    }

    if (waiter != null)
    {
      trc("dequeued a waiter");
      // TODO probably shouldn't execute the getter's callback on the putting thread
      // If this callback is moved to another thread, make sure shutdownComplete does not get
      // invoked until after this callback is completed
      waiter.onSuccess(obj);
    }
    else
    {
      trc("enqueued an idle object");
    }
    if (shutdown != null)
    {
      // Now that the final user callback has been executed, pool shutdown is complete
      finishShutdown(shutdown);
    }
  }

  @Override
  public void dispose(T obj)
  {
    destroy(obj, true);
  }

  private void destroy(T obj, boolean bad)
  {
    trc("disposing a pooled object");
    _lifecycle.destroy(obj, bad, new Callback<T>()
    {
      @Override
      public void onSuccess(T t)
      {
        boolean create;
        synchronized (_lock)
        {
          _totalDestroyed++;
          create = objectDestroyed();
        }
        if (create)
        {
          create();
        }
      }

      @Override
      public void onError(Throwable e)
      {
        boolean create;
        synchronized (_lock)
        {
          _destroyErrors++;
          create = objectDestroyed();
        }
        if (create)
        {
          create();
        }
        // TODO log this error!
      }
    });
  }

  /**
   * This method is safe to call while holding the lock.
   * @return true if another object creation should be initiated
   */
  private boolean objectDestroyed()
  {
    boolean create;
    synchronized (_lock)
    {
      _poolSize--;
      create = shouldCreate();
      shutdownIfNeeded();
    }
    return create;
  }

  /**
   * This method is safe to call while holding the lock.  DO NOT
   * call any callbacks in this method!
   * @return true if another object creation should be initiated.
   */
  private boolean shouldCreate()
  {
    boolean result = false;
    synchronized (_lock)
    {
      if (_state == State.RUNNING)
      {
        if (_poolSize >= _maxSize)
        {
          // If we pass up an opportunity to create an object due to full pool, the next
          // timeout is not necessarily caused by any previous creation failure.  Need to
          // think about this a little more.  What if the pool is full due to pending creations
          // that eventually fail?
          _lastCreateError = null;
        }
        else if (_waiters.size() > 0)
        {
          _poolSize++;
          result = true;
        }
      }
    }
    return result;
  }

  /**
   * DO NOT call this method while holding the lock!  It invokes user code.
   */
  private void create()
  {
    trc("initiating object creation");
    _lifecycle.create(new Callback<T>() {
      @Override
      public void onSuccess(T t)
      {
        synchronized (_lock)
        {
          _totalCreated++;
          _lastCreateError = null;
        }
        add(t);
      }

      @Override
      public void onError(Throwable e)
      {
        boolean create;
        Collection<Callback<T>> waitersDenied = Collections.emptyList();
        synchronized (_lock)
        {
          _createErrors++;
          _lastCreateError = e;
          create = objectDestroyed();
          if (!_waiters.isEmpty())
          {
            waitersDenied = cancelWaiters();
          }
        }
        // Note we drain all waiters if a create fails.  When a create fails, rate-limiting
        // logic may be applied.  In this case, we may be initiating creations at a lower rate
        // than incoming requests.  While creations are suppressed, it is better to deny all
        // waiters and let them see the real reason (this exception) rather than keep them around
        // to eventually get an unhelpful timeout error
        for (Callback<T> denied : waitersDenied)
        {
          denied.onError(e);
        }
        if (create)
        {
          create();
        }
        LOG.error(_poolName + ": object creation failed", e);
      }
    });
  }

  private void timeoutObjects()
  {
    Collection<T> idle = reap(_idle, _idleTimeout);
    if (idle.size() > 0)
    {
      LOG.debug("{}: disposing {} objects due to idle timeout", _poolName, idle.size());
      for (T obj : idle)
      {
        destroy(obj, false);
      }
    }
  }

  private <U> Collection<U> reap(Queue<TimedObject<U>> queue, long timeout)
  {
    List<U> toReap = new ArrayList<U>();
    long now = System.currentTimeMillis();
    long target = now - timeout;

    synchronized (_lock)
    {
      for (TimedObject<U> p; (p = queue.peek()) != null && p.getTime() < target; )
      {
        toReap.add(queue.poll().get());
      }
    }
    return toReap;
  }

  private void shutdownIfNeeded()
  {
    Callback<None> shutdown = checkShutdownComplete();
    if (shutdown != null)
    {
      finishShutdown(shutdown);
    }
  }

  private Callback<None> checkShutdownComplete()
  {
    Callback<None> done = null;
    final State state;
    final int waiters;
    final int idle;
    final int poolSize;
    synchronized (_lock)
    {
      // Save state for logging outside synchronized block
      state = _state;
      waiters = _waiters.size();
      idle = _idle.size();
      poolSize = _poolSize;

      // Now compare against the same state that will be logged
      if (state == State.SHUTTING_DOWN && waiters == 0 && idle == poolSize)
      {
        _state = State.STOPPED;
        done = _shutdownCallback;
        _shutdownCallback = null;
      }
    }
    if (state == State.SHUTTING_DOWN && done == null)
    {
      LOG.info("{}: {} waiters and {} objects outstanding before shutdown", new Object[]{ _poolName, waiters, poolSize - idle });
    }
    return done;
  }

  private void finishShutdown(Callback<None> shutdown)
  {
    ScheduledFuture<?> future = _objectTimeoutFuture;
    if (future != null)
    {
      future.cancel(false);
    }

    LOG.info("{}: {}", _poolName, "shutdown complete");

    shutdown.onSuccess(None.none());
  }

  private static class TimedObject<T>
  {
    private final T _obj;
    private final long _time;

    public TimedObject(T obj)
    {
      _obj = obj;
      _time = System.currentTimeMillis();
    }

    public T get()
    {
      return _obj;
    }

    public long getTime()
    {
      return _time;
    }
  }

  private void trc(Object toLog)
  {
    LOG.trace("{}: {}", _poolName, toLog);
  }

}
