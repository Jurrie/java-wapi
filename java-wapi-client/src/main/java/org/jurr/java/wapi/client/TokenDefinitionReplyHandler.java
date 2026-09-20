package org.jurr.java.wapi.client;

import java.util.function.Consumer;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.node.WapiNodeDefinitionCodec;
import org.jurr.java.wapi.node.WapiTokenDefinition;
import org.jurr.java.wapi.protocol.WapiEventDecoder.StreamRecord;

/** The reply to a definition request, as for C {@code wGetTokenDef}: the definition with the token's own hash. */
final class TokenDefinitionReplyHandler extends ReplyHandler<WapiTokenDefinition>
{
	private final WapiToken token;

	TokenDefinitionReplyHandler(final WapiToken token)
	{
		this.token = token;
	}

	@Override
	void onRecord(final StreamRecord streamRecord, final Consumer<WapiEvent> events)
	{
		if (streamRecord.isEvent())
		{
			events.accept(streamRecord.event());
		}
		else if (streamRecord.isNodeDefinition())
		{
			final var definition = WapiNodeDefinitionCodec.parse(streamRecord.rawBytes());
			if (definition.hash() == Integer.toUnsignedLong(token.hash()))
			{
				complete(new WapiTokenDefinition(token, streamRecord.rawBytes(), definition));
			}
		}
		else if (streamRecord.isEndOfData())
		{
			// As for a node, which the console answers with the definitions of its children
			fail(noDefinition());
		}
	}

	@Override
	WapiTokenDefinition onTimeout(final Consumer<WapiEvent> events)
	{
		throw noDefinition();
	}

	private WapiException noDefinition()
	{
		return new WapiException("No definition received for " + token.name(), WapiError.WRECV_ERROR);
	}
}
