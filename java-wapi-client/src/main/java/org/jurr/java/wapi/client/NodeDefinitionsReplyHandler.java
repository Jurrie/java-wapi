package org.jurr.java.wapi.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jurr.java.wapi.WapiError;
import org.jurr.java.wapi.WapiEvent;
import org.jurr.java.wapi.WapiException;
import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.node.WapiNodeDefinition;
import org.jurr.java.wapi.node.WapiNodeDefinitionCodec;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.jurr.java.wapi.protocol.WapiEventDecoder.StreamRecord;

/**
 * The reply to a definition request for a node, or for the top-level nodes: all definitions up to
 * {@code 0xde}. A problem is reported only after the whole reply, so that none of it is left behind.
 */
final class NodeDefinitionsReplyHandler extends ReplyHandler<List<WapiNodeDefinition>>
{
	private final WapiToken node;
	private final Consumer<WapiNodeDefinition> onDefinition;
	private final List<WapiNodeDefinition> definitions = new ArrayList<>();
	private WapiException error;

	/**
	 * @param node node whose children are requested, or {@code null} for the top-level nodes
	 * @param onDefinition receives every definition as it is parsed
	 */
	NodeDefinitionsReplyHandler(final WapiToken node, final Consumer<WapiNodeDefinition> onDefinition)
	{
		this.node = node;
		this.onDefinition = onDefinition;
	}

	@Override
	void onRecord(final StreamRecord streamRecord, final Consumer<WapiEvent> events)
	{
		if (streamRecord.isEvent())
		{
			events.accept(streamRecord.event());
		}
		else if (streamRecord.isEndOfData())
		{
			if (error != null)
			{
				fail(error);
			}
			else
			{
				complete(definitions);
			}
		}
		else if (streamRecord.isNodeDefinition())
		{
			// The timeout applies to silence, so that large nodes can arrive in many frames
			progress();
			try
			{
				final var definition = WapiNodeDefinitionCodec.parse(streamRecord.rawBytes());
				if (node != null && definition.hash() == Integer.toUnsignedLong(node.hash()) && error == null)
				{
					error = new WapiException("Not a node: " + node.name(), WapiError.WNODE);
				}
				onDefinition.accept(definition);
				definitions.add(definition);
			}
			catch (final WapiException e)
			{
				if (error == null)
				{
					error = new WapiException("Unparsable node definition: " + WapiBinaryDataCodec.encodeHex(streamRecord.rawBytes()), WapiError.WNODE, e);
				}
			}
		}
	}

	@Override
	List<WapiNodeDefinition> onTimeout(final Consumer<WapiEvent> events)
	{
		throw new WapiException("Timed out waiting for the node definitions of " + (node == null ? "the root" : node.name()), WapiError.WRECV_ERROR);
	}

	@Override
	boolean restartsTimeoutOnProgress()
	{
		return true;
	}
}
