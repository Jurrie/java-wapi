package org.jurr.java.wapi.client;

import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.WapiValue;
import org.jurr.java.wapi.protocol.WapiEventDecoder.StreamRecord;

/** The reply to a data request for a parameter, as for {@code wGetToken}: its first record with the token's hash. */
final class TokenReplyHandler extends ReplyHandler<WapiValue>
{
	private final WapiToken token;
	private final UnaryOperator<WapiValue> conversion;

	/** @param conversion converts the received value to the requested type */
	TokenReplyHandler(final WapiToken token, final UnaryOperator<WapiValue> conversion)
	{
		this.token = token;
		this.conversion = conversion;
	}

	@Override
	void onRecord(final StreamRecord streamRecord, final Consumer<WapiEvent> events)
	{
		if (!streamRecord.isEvent())
		{
			return;
		}
		final var event = streamRecord.event();
		if (event.token().hash() == token.hash())
		{
			complete(conversion.apply(event.value()));
		}
		else
		{
			events.accept(event);
		}
	}

	@Override
	WapiValue onTimeout(final Consumer<WapiEvent> events)
	{
		throw new WapiException("Timed out waiting for token " + token.name());
	}
}
