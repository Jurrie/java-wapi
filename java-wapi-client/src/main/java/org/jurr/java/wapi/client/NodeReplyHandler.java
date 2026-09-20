package org.jurr.java.wapi.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.protocol.WapiEventDecoder.StreamRecord;

/**
 * The reply to a data request for a node or parameter: the token's own record for a parameter, or
 * the records of all parameters below a node up to {@code 0xde}. Records that arrive before a
 * parameter's own record were events after all.
 */
final class NodeReplyHandler extends ReplyHandler<List<StreamRecord>>
{
	private final WapiToken token;
	private final List<StreamRecord> nodeRecords = new ArrayList<>();

	NodeReplyHandler(final WapiToken token)
	{
		this.token = token;
	}

	@Override
	void onRecord(final StreamRecord steamRecord, final Consumer<WapiEvent> events)
	{
		if (steamRecord.isNodeDefinition())
		{
			return;
		}
		if (steamRecord.isEndOfData())
		{
			if (nodeRecords.isEmpty())
			{
				// A reply of only 0xde: the console does not know the token. This cannot be told apart
				// from a 0xde left over from an earlier reply.
				fail(new WapiException("Unknown node or parameter: " + token.name(), WapiError.WNODE));
			}
			else
			{
				complete(new ArrayList<>(nodeRecords));
			}
		}
		else if (steamRecord.event().token().hash() == token.hash())
		{
			deliver(events);
			complete(List.of(steamRecord));
		}
		else
		{
			nodeRecords.add(steamRecord);
			// The timeout applies to silence, so that large nodes can arrive in many frames
			progress();
		}
	}

	@Override
	List<StreamRecord> onTimeout(final Consumer<WapiEvent> events)
	{
		deliver(events);
		throw new WapiException("Timed out waiting for node " + token.name());
	}

	@Override
	boolean restartsTimeoutOnProgress()
	{
		return true;
	}

	private void deliver(final Consumer<WapiEvent> events)
	{
		for (final var nodeRecord : nodeRecords)
		{
			events.accept(nodeRecord.event());
		}
		nodeRecords.clear();
	}
}
