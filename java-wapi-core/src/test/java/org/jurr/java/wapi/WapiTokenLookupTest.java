package org.jurr.java.wapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class WapiTokenLookupTest
{
	@Test
	void createsCanonicalHashFromNameAndLooksItUp()
	{
		final var registry = new WapiTokenRegistry();
		final var token = registry.findOrCreateByName("ch.1.mute");

		assertEquals(token, registry.findByHash(token.hash()));
		assertEquals("ch.1.mute", token.name());
	}

	@Test
	void storesOpaqueTokensByHashWithoutSharingTheUnknownName()
	{
		final var registry = new WapiTokenRegistry();
		final var first = registry.findOrCreateByHash(0x01020304);
		final var second = registry.findOrCreateByHash(0x05060708);

		assertFalse(first.hasKnownName());
		assertFalse(second.hasKnownName());
		assertSame(first, registry.findByHash(first.hash()));
		assertSame(second, registry.findByHash(second.hash()));
		assertSame(first, registry.findOrCreateByHash(first.hash()));
		assertThrows(WapiException.class, () -> registry.findByName(WapiToken.UNKNOWN_NAME));
		assertThrows(WapiException.class, () -> registry.findOrCreateByName(WapiToken.UNKNOWN_NAME));
		assertFalse(registry.asMap().containsKey(WapiToken.UNKNOWN_NAME));
	}

	@Test
	void registeredTokenIsFoundByName()
	{
		final var registry = new WapiTokenRegistry();
		final var token = new WapiToken("cfg.mon.1.pan");

		registry.register(token);

		assertEquals(token, registry.findByName("cfg.mon.1.pan"));
	}

	@Test
	void namedTokenReplacesItsOpaqueHashEntry()
	{
		final var registry = new WapiTokenRegistry();
		final var named = new WapiToken("ch.1.mute");
		final var opaque = registry.findOrCreateByHash(named.hash());

		registry.register(named);

		assertSame(named, registry.findByHash(named.hash()));
		assertSame(named, registry.findByName(named.name()));
		assertFalse(opaque.hasKnownName());
	}
}