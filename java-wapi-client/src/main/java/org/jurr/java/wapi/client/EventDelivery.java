package org.jurr.java.wapi.client;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers the events and lost connections of a {@link WapiClient}: events are queued for
 * {@code wGetParsedEvents}, and the event and connection listeners are called on a dispatcher
 * thread, in arrival order, so that a listener may call the client.
 */
final class EventDelivery
{
	private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	/** Maximum number of events kept for {@code wGetParsedEvents}; the oldest is dropped when full. */
	static final int QUEUE_CAPACITY = 10_000;

	private final BoundedQueue<WapiEvent> queue = new BoundedQueue<>(QUEUE_CAPACITY);
	private final List<WapiEventListener> eventListeners = new CopyOnWriteArrayList<>();
	private final List<WapiConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
	/** Calls the listeners; guarded by {@code this}. */
	private ExecutorService dispatcher;

	/** Starts the dispatcher thread, unless it runs already. */
	synchronized void start()
	{
		if (dispatcher == null)
		{
			dispatcher = Executors.newSingleThreadExecutor(runnable -> Threads.daemon(runnable, "wapi-events"));
		}
	}

	/** Stops the dispatcher thread after the listener calls already queued. */
	synchronized void stop()
	{
		if (dispatcher != null)
		{
			dispatcher.shutdown();
			dispatcher = null;
		}
	}

	private synchronized ExecutorService dispatcher()
	{
		return dispatcher;
	}

	/** @see WapiClient#addEventListener(WapiEventListener) */
	WapiSubscription addEventListener(final WapiEventListener listener)
	{
		if (listener == null)
		{
			throw new WapiException("Listener is required");
		}
		eventListeners.add(listener);
		return () -> eventListeners.remove(listener);
	}

	/** @see WapiClient#addConnectionListener(WapiConnectionListener) */
	WapiSubscription addConnectionListener(final WapiConnectionListener listener)
	{
		if (listener == null)
		{
			throw new WapiException("Listener is required");
		}
		connectionListeners.add(listener);
		return () -> connectionListeners.remove(listener);
	}

	/** Queues an event and calls the event listeners that are registered now. */
	void offer(final WapiEvent event)
	{
		queue.offer(event);
		dispatch(event);
	}

	/** Queues the events of one packet at once, so that a waiting caller sees all of them, and calls the listeners. */
	void offerAll(final List<WapiEvent> events)
	{
		queue.offerAll(events);
		for (final var event : events)
		{
			dispatch(event);
		}
	}

	/** Calls the event listeners that are registered now on the dispatcher thread. */
	private void dispatch(final WapiEvent event)
	{
		if (eventListeners.isEmpty())
		{
			return;
		}
		final var listeners = new ArrayList<WapiEventListener>(eventListeners);
		final var events = dispatcher();
		if (events != null)
		{
			events.execute(() -> {
				for (final var listener : listeners)
				{
					try
					{
						listener.onEvent(event);
					}
					catch (final RuntimeException e)
					{
						// A failing listener must not stop the others or later events
						LOGGER.warn("An event listener failed on {} = {}", event.token().name(), event.value(), e);
					}
				}
			});
		}
	}

	/** Calls the connection listeners that are registered now on the dispatcher thread, after the events before. */
	void connectionLost(final WapiException cause)
	{
		if (connectionListeners.isEmpty())
		{
			return;
		}
		final var listeners = new ArrayList<WapiConnectionListener>(connectionListeners);
		final var events = dispatcher();
		if (events != null)
		{
			events.execute(() -> {
				for (final var listener : listeners)
				{
					try
					{
						listener.onConnectionLost(cause);
					}
					catch (final RuntimeException e)
					{
						// A failing listener must not stop the others
						LOGGER.warn("A connection listener failed on the lost connection: {}", cause.getMessage(), e);
					}
				}
			});
		}
	}

	/** Takes queued events, oldest first, without waiting. */
	List<WapiEvent> drain(final int maxEvents)
	{
		return queue.drain(maxEvents);
	}

	/**
	 * Waits until an event is queued, {@link #wakeUp()} is called, or the deadline passes.
	 *
	 * @param deadline {@link System#nanoTime()} to wait until, or {@link Long#MAX_VALUE} to wait without limit
	 */
	void await(final long deadline) throws InterruptedException
	{
		queue.awaitItem(deadline);
	}

	/** Ends waits for events, as when the connection is lost. */
	void wakeUp()
	{
		queue.wakeUp();
	}

	/** Waits until the listeners have been called for every event delivered so far; for tests. */
	void awaitListeners() throws InterruptedException
	{
		final var events = dispatcher();
		if (events != null)
		{
			try
			{
				events.submit(() -> {
				}).get();
			}
			catch (final ExecutionException e)
			{
				throw new IllegalStateException(e);
			}
		}
	}
}
