# WAPI Node Definition Format

A node definition describes one node or parameter: its name, type, unit and value range. WING sends it in response to a node-definition request (`0xDD`). The layout is in the PDF, section "Node Definition Response".

## Response structure

```
0xdf, len.w, [len.l], parent.l, hash.l, index.w, namelen.b, name, longnamelen.b, longname, flags.w, <type-specific fields>
```

If `len.w` is 0, the length is in the following `len.l` field instead. The type-specific fields are:

- lin/log float: `min.f, max.f, steps.l`
- integer: `min.l, max.l`
- string enum: `count.w, [itemlen.b, item, longitemlen.b, longitem] * count`
- float enum: `count.w, [itemval.f, longitemlen.b, longitem] * count`
- string: `maxlen.w`
- node and fader level: none

## Derived metadata

Three properties are derived from the `flags` word (PDF Table 3, Node Flags):

- unit: bits 0..3
- node type: bits 4..7
- read-only: bit 9 (`0x0200`)

This layout is confirmed with definitions captured from a console for the children of `ch.1`: for example `name` has flags `0x0070` (string), `fdr` `0x0031` (fader level, dB), `mute` `0x0040` (integer) and the read-only `$col` `0x0240`. The captured bytes are test fixtures in `WapiNodeDefinitionCodecTest`.

## Parent and index

Captured definitions show what `parent` and `index` hold (fixtures in `WapiNodeTreeTest`):

- `parent` is the hash of the parent node, not an index: the children of `ch.1` carry `878392cf`, the hash of `ch.1`. Top-level nodes such as `ch` and `cfg` carry 0.
- A node is addressed either by number or by name. When `index` is not 0, the node is addressed by that number:
  - `ch.1` has an empty name and index 1 (1..40 for `ch.1`..`ch.40`); its long name is the number as text.
  - The dynamics parameter `ch.3.dyn.2` has index 2 and the name `ratio`, which comes from the loaded dynamics model. This is the "dynamic parameters anonymization" from the PDF: plugin and effect parameters are addressed by number, whichever model is loaded.
- A node with index 0, such as `ch.1.fdr`, is addressed by its name.
- The node's hash is the hash of its full dotted path, built from these segments: `ch.1.fdr`, `ch.3.dyn.2`.

`WapiNodeKind` maps the type values 0..7 to:

- `NODE`
- `LIN_FLOAT`
- `LOG_FLOAT`
- `FADER_LEVEL`
- `INTEGER`
- `STRING_ENUM`
- `FLOAT_ENUM`
- `STRING`

## Java representation

The Java model is the record `WapiNodeDefinition`, with these components (accessors such as `name()` and `hash()`):

- `length`
- `parentHash`
- `hash`
- `index`
- `name`
- `longName`
- `flags`
- `metadata`

and these derived methods:

- `nodeKind()`
- `unitCode()`
- `isReadOnly()`
- `pathSegment()`: the index when it is not 0, otherwise the name

`WapiNodeTree` links definitions by hash: `getChildren`, `getParent`, `findByHash`, and `getTopLevelNodes` for the nodes with parent hash 0.

For typed leaf nodes, `metadata` follows the PDF layout:

- linear/log float nodes expose `minFloat`, `maxFloat`, and `steps`
- integer nodes expose `minInt` and `maxInt`
- string nodes expose `maxStringLength`
- string/float enum nodes expose their items in `enumItems`: each item has the value that is read and set (a string, or a float for a float enum) and a long name as display label. In captured definitions the long name is often empty (`ch.1.mon`: `A`, `B`, `A+B`), and sometimes set (`ch.1.ptap`: `IN` labelled `INPUT`; `ch.3.dyn.2`: `1.1` labelled `1.1:1`). `enumValues()` gives the values as text.

Metadata is nullable for node definitions whose payload ends after the flags
word or whose type has no additional fields, such as ordinary tree nodes and
fader-level nodes.
