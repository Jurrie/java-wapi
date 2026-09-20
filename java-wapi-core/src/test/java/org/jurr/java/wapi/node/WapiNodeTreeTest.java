package org.jurr.java.wapi.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jurr.java.wapi.WapiToken;
import org.jurr.java.wapi.protocol.WapiBinaryDataCodec;
import org.junit.jupiter.api.Test;

class WapiNodeTreeTest
{
	// Definitions captured from a WING console. Top-level nodes have parent hash 00000000.
	private static final String CH = "df00170000000070b101390000026368074348414e4e454c0000";
	private static final String CFG = "df001100000000edca7af9000003636667000000";
	// Numbered children of ch (parent 70b10139): empty name, index 1 and 2, long name "1" and "2"
	private static final String CH_1 = "df000f70b10139878392cf00010001310000";
	private static final String CH_2 = "df000f70b10139deb8410c00020001320000";
	// Named children of ch.1 (parent 878392cf): index 0
	private static final String FDR = "df0016878392cf38ae75c20000036664720546414445520031";
	private static final String NAME = "df0018878392cfc6ad548d0000046e616d65044e414d4500700010";
	// Dynamics parameter of ch.3 (parent 290203c6 = "ch.3.dyn"): name "ratio" from the loaded model, index 2
	static final String RATIO = "df00b5290203c6fef61e36000205726174696f05524154494f006000113f8ccccd05312e313a313f99999a05312e323a313fa6666605312e333a313fc0000005312e353a313fd9999a05312e373a314000000003323a314020000005322e353a314040000003333a314060000005332e353a314080000003343a3140a0000003353a3140c0000003363a314100000003383a31412000000431303a3141a000000432303a31424800000435303a3142c80000053130303a31";
	private static final String MUTE = "df001e878392cff50f69f80000046d757465044d55544500400000000000000001";

	@Test
	void linksCapturedDefinitionsByParentHash()
	{
		final var ch = parse(CH);
		final var cfg = parse(CFG);
		final var ch1 = parse(CH_1);
		final var ch2 = parse(CH_2);
		final var fdr = parse(FDR);
		final var name = parse(NAME);
		final var mute = parse(MUTE);
		final var tree = new WapiNodeTree();
		for (final var definition : List.of(ch, cfg, ch1, ch2, fdr, name, mute))
		{
			tree.add(definition);
		}

		assertEquals(List.of(ch1, ch2), tree.getChildren(ch));
		assertEquals(List.of(fdr, name, mute), tree.getChildren(ch1));
		assertSame(ch1, tree.getParent(fdr));
		assertSame(ch, tree.getParent(ch2));
	}

	@Test
	void findsTopLevelNodesAndNodesByHash()
	{
		final var ch = parse(CH);
		final var cfg = parse(CFG);
		final var ch1 = parse(CH_1);
		final var tree = new WapiNodeTree();
		tree.add(ch);
		tree.add(cfg);
		tree.add(ch1);

		assertEquals(List.of(ch, cfg), tree.getTopLevelNodes());
		assertNull(tree.getParent(ch));
		assertSame(ch1, tree.findByHash(0x878392CFL));
		assertTrue(tree.containsHash(0x70B10139L));
		assertFalse(tree.containsHash(0x38AE75C2L));
	}

	@Test
	void replacesDefinitionWithSameHash()
	{
		final var ch = parse(CH);
		final var first = parse(CH_1);
		final var second = parse(CH_1);
		final var tree = new WapiNodeTree();
		tree.add(ch);
		tree.add(first);
		tree.add(second);

		assertEquals(List.of(second), tree.getChildren(ch));
		assertSame(second, tree.findByHash(0x878392CFL));
	}

	@Test
	void exposesParentHashIndexAndPathSegment()
	{
		final var ch = parse(CH);
		final var ch1 = parse(CH_1);
		final var fdr = parse(FDR);

		assertEquals(WapiNodeTree.ROOT_HASH, ch.parentHash());
		assertEquals(0x70B10139L, ch1.parentHash());
		assertEquals(0x878392CFL, fdr.parentHash());
		// A numbered node has an empty name and its number as index
		assertEquals("", ch1.name());
		assertEquals(1, ch1.index());
		assertEquals("1", ch1.pathSegment());
		// A named node has index 0
		assertEquals(0, fdr.index());
		assertEquals("fdr", fdr.pathSegment());
		// The node hash is the hash of the full path built from the segments
		assertEquals(new WapiToken("ch." + ch1.pathSegment() + "." + fdr.pathSegment()).hash(), (int) fdr.hash());
	}

	@Test
	void addressesDynamicParameterByIndex()
	{
		// PDF "Dynamic parameters anonymization": plugin parameters are addressed by number
		final var ratio = parse(RATIO);

		assertEquals("ratio", ratio.name());
		assertEquals(2, ratio.index());
		assertEquals("2", ratio.pathSegment());
		assertEquals(new WapiToken("ch.3.dyn." + ratio.pathSegment()).hash(), (int) ratio.hash());
	}

	private static WapiNodeDefinition parse(final String hex)
	{
		return WapiNodeDefinitionCodec.parse(WapiBinaryDataCodec.decodeHex(hex, 1024));
	}
}
