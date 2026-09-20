package org.jurr.java.wapi.client;

import java.util.function.Consumer;

import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.protocol.WapiEventDecoder.StreamRecord;

/**
 * Collects the reply to one request. The client offers it every record received on channel 1 until
 * it is done; the handler decides per record whether it belongs to the reply. Records that do not
 * belong to it are events, which the handler passes on, possibly later, as for a node reply that
 * turns out to be a parameter's.
 *
 * @param <T> type of the result
 */
abstract class ReplyHandler<T>
{
	private T result;
	private WapiException failure;
	private boolean done;
	private boolean delivered;
	private boolean progressed;

	/**
	 * Offers a record of the reply, or an event received while waiting for it.
	 *
	 * @param events receives the records that are not part of the reply
	 * @throws WapiException if the record cannot be handled; the request then fails with it
	 */
	abstract void onRecord(StreamRecord streamRecord, Consumer<WapiEvent> events);

	/**
	 * Ends a reply that did not complete in time.
	 *
	 * @param events receives the records held back so far, which are not part of a reply after all
	 * @return the result, for a reply that may end with a timeout
	 * @throws WapiException otherwise
	 */
	abstract T onTimeout(Consumer<WapiEvent> events);

	/** {@return whether the timeout restarts with every record of the reply, for replies of many frames} */
	boolean restartsTimeoutOnProgress()
	{
		return false;
	}

	protected final void complete(final T value)
	{
		if (!done)
		{
			result = value;
			done = true;
		}
	}

	protected final void fail(final WapiException error)
	{
		if (!done)
		{
			failure = error;
			done = true;
		}
	}

	/** Marks that a record of the reply arrived. */
	protected final void progress()
	{
		progressed = true;
	}

	final boolean isDone()
	{
		return done;
	}

	/** Marks a done reply as handed over: the rest of the packet it ended in has been processed too. */
	final void deliver()
	{
		delivered = true;
	}

	/** {@return whether the reply is done and the events received with it have been delivered} */
	final boolean isDelivered()
	{
		return delivered;
	}

	/** {@return whether a record of the reply arrived since the previous call} */
	final boolean takeProgress()
	{
		final boolean value = progressed;
		progressed = false;
		return value;
	}

	/**
	 * {@return the result}
	 *
	 * @throws WapiException if the request failed
	 */
	final T result()
	{
		if (failure != null)
		{
			throw failure;
		}
		return result;
	}
}
