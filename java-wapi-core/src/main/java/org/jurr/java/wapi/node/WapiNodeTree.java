package org.jurr.java.wapi.node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jurr.java.wapi.WapiException;

/**
 * Insertion-ordered node-definition tree. Nodes are identified by their hash and linked to their
 * parent through the parent hash; top-level nodes have parent hash {@value #ROOT_HASH}.
 */
public class WapiNodeTree
{
	/** Parent hash of the top-level nodes (the root has no definition of its own). */
	public static final long ROOT_HASH = 0L;

	private final Map<Long, WapiNodeDefinition> byHash = new LinkedHashMap<>();
	private final Map<Long, List<WapiNodeDefinition>> childrenByParentHash = new LinkedHashMap<>();

	/** Creates an empty tree. */
	public WapiNodeTree()
	{
	}

	/**
	 * Adds a node definition, or replaces the definition with the same hash.
	 *
	 * @param node definition to add; must not be {@code null}
	 * @throws WapiException if {@code node} is {@code null}
	 */
	public void add(final WapiNodeDefinition node)
	{
		if (node == null)
		{
			throw new WapiException("Node definition is required");
		}
		final var replaced = byHash.put(node.hash(), node);
		if (replaced != null)
		{
			childrenByParentHash.get(replaced.parentHash()).remove(replaced);
		}
		childrenByParentHash.computeIfAbsent(node.parentHash(), parentHash -> new ArrayList<>()).add(node);
	}

	/** {@return unmodifiable snapshot of the top-level nodes, in insertion order} */
	public List<WapiNodeDefinition> getTopLevelNodes()
	{
		return childrenOf(ROOT_HASH);
	}

	/**
	 * Finds the stored parent definition for a node.
	 *
	 * @param node node whose parent is requested; null is treated as absent
	 * @return stored parent definition, or {@code null} for a top-level node or a parent that is not stored
	 */
	public WapiNodeDefinition getParent(final WapiNodeDefinition node)
	{
		if (node == null)
		{
			return null;
		}
		return byHash.get(node.parentHash());
	}

	/**
	 * Returns a snapshot of the direct children stored for a node.
	 *
	 * @param node parent node; null is treated as having no children
	 * @return unmodifiable child list in insertion order, or an empty list when none are stored
	 */
	public List<WapiNodeDefinition> getChildren(final WapiNodeDefinition node)
	{
		if (node == null)
		{
			return List.of();
		}
		return childrenOf(node.hash());
	}

	/**
	 * {@return whether a node with that hash is stored}
	 *
	 * @param hash node hash as an unsigned 32-bit value
	 */
	public boolean containsHash(final long hash)
	{
		return byHash.containsKey(hash);
	}

	/**
	 * {@return matching definition, or {@code null}}
	 *
	 * @param hash node hash as an unsigned 32-bit value
	 */
	public WapiNodeDefinition findByHash(final long hash)
	{
		return byHash.get(hash);
	}

	private List<WapiNodeDefinition> childrenOf(final long parentHash)
	{
		final var children = childrenByParentHash.get(parentHash);
		if (children == null)
		{
			return List.of();
		}
		return List.copyOf(children);
	}
}
