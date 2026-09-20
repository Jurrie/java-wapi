package org.jurr.java.wapi.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Received items, such as the events that {@code wGetParsedEvents} returns, in the order they
 * arrived. The queue is bounded: when it is full, the oldest item is dropped, so an application that
 * only uses listeners does not fill its memory.
 *
 * @param <T> type of the items
 */
final class BoundedQueue<T>
{
	private final int capacity;
	private final Deque<T> items = new ArrayDeque<>();

	/** @param capacity maximum number of items kept */
	BoundedQueue(final int capacity)
	{
		this.capacity = capacity;
	}

	/** Adds an item, dropping the oldest one when the queue is full. */
	synchronized void offer(final T item)
	{
		if (items.size() == capacity)
		{
			items.removeFirst();
		}
		items.addLast(item);
		notifyAll();
	}

	/** Adds items at once, so that a waiting caller sees all of them; drops the oldest when full. */
	synchronized void offerAll(final Iterable<T> added)
	{
		for (final var item : added)
		{
			if (items.size() == capacity)
			{
				items.removeFirst();
			}
			items.addLast(item);
		}
		notifyAll();
	}

	/**
	 * Takes queued items, oldest first, without waiting.
	 *
	 * @param maxItems maximum number of items to take
	 * @return the items taken, possibly none
	 */
	synchronized List<T> drain(final int maxItems)
	{
		final var count = Math.min(maxItems, items.size());
		final var drained = new ArrayList<T>(count);
		for (var i = 0; i < count; i++)
		{
			drained.add(items.removeFirst());
		}
		return drained;
	}

	/**
	 * Waits until an item is queued, {@link #wakeUp()} is called, or the deadline passes.
	 *
	 * @param deadline {@link System#nanoTime()} to wait until, or {@link Long#MAX_VALUE} to wait without limit
	 */
	synchronized void awaitItem(final long deadline) throws InterruptedException
	{
		if (!items.isEmpty())
		{
			return;
		}
		if (deadline == Long.MAX_VALUE)
		{
			wait();
			return;
		}
		final long remaining = deadline - System.nanoTime();
		if (remaining > 0)
		{
			wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
		}
	}

	/** Ends waits, as when the connection is lost. */
	synchronized void wakeUp()
	{
		notifyAll();
	}
}
