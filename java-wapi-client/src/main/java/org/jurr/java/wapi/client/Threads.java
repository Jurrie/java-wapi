package org.jurr.java.wapi.client;

/** Helpers for the client's background threads. */
final class Threads
{
	private Threads()
	{
	}

	/** {@return a daemon thread, so that the client's threads do not keep an application running} */
	static Thread daemon(final Runnable runnable, final String name)
	{
		final var thread = new Thread(runnable, name);
		thread.setDaemon(true);
		return thread;
	}
}
