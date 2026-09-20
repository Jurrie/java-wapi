package org.jurr.java.wapi.node;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.jurr.java.wapi.WapiToken;
import org.junit.jupiter.api.Test;

class WapiTokenDefinitionTest
{
	@Test
	void retainsParsedNodeDefinition()
	{
		final var token = new WapiToken("ch.1.mute");
		final var definition = new WapiNodeDefinition(0, 0, token.hash(), 1,
				"mute", "Mute", 0);
		final var tokenDefinition = new WapiTokenDefinition(token, new byte[] { 1 }, definition);

		assertSame(definition, tokenDefinition.nodeDefinition());
	}
}