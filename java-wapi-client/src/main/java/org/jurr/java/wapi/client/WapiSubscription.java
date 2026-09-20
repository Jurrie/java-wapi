package org.jurr.java.wapi.client;

/** Handle for releasing a listener registration. */
public interface WapiSubscription extends AutoCloseable
{
	/** Removes the registration represented by this handle; repeated calls are harmless. */
	@Override
	void close();
}