# AGENTS.md

This repository contains a Java implementation of the Behringer WING remote control library ("wapi"), based on the C reference library and the protocol documentation.

## Mission

Provide a Java library with the same features as the C wapi library, built on the WING native/binary protocol described in the PDF, and covered by unit tests.

## API style

- The public API matches the C functions: method names and behaviour follow the C library (`wOpen`, `wGetTokenFloat`, `wSetTokenInt`, `wGetParsedEvents`, ...). The signatures follow Java: `void` instead of a status code, `List` or `Optional` results instead of output buffers or `null`, and overloads with a `Duration` instead of the C `...Timed` functions.
- Everything else follows Java conventions: Java types and collections, exceptions for errors, immutable value objects, and the usual Java naming for classes, fields and internal methods.
- Javadoc of a public method that maps to a C function describes the C semantics it implements and any deliberate difference.
- The code targets Java 17 (`.sdkmanrc` selects it with SDKMAN!). Value objects are records, with record accessors (`name()`, not `getName()`); boolean queries keep `is`/`has`. A record holding an array copies it in and out and compares it by content. Local variables use `var` wherever the type stays the same; keep the explicit type where `var` would change it (such as `long x = 0`).

## Protocol rules

- Preserve the protocol boundaries: channel framing, binary stream tokens, node definitions, meter requests and discovery payloads.
- Do not add application-level concepts that have no counterpart in the C library or the protocol.
- Validate every protocol claim against the PDF or the C reference, at byte level. Do not rely on labels or names alone.
- When a live console or emulator disagrees with the PDF, document the discrepancy and prefer the PDF until there is captured evidence.
- State assumptions explicitly when the PDF or the C library is incomplete.

## Repository context

- `docs/WING Remote Protocols.pdf`: protocol reference. The chapters "WING native / binary data interface" and "wapi Reference Guide" are the most relevant.
- `java-wapi-linux/`: the C reference (`wapi.h`, `wext.h`, `libwapi.a`). `wapi.h` contains the token enum; `wext.h` lists the public functions. This directory is not pushed to GitHub to avoid possible licensing issues.
- `java-wapi-core`: protocol codecs, tokens, values, node definitions, meters and discovery.
- `java-wapi-client`: TCP transport, UDP meter receiver and discovery, and `WapiClient`.
- `java-wapi-examples`: the examples and diagnostic tools; not part of the library.
- `docs/PROTOCOL-PARITY-CHECKLIST.md`: known gaps against the PDF and the C library.

## Workflow

1. Find the relevant codec or client method and the matching section of the PDF or C function.
2. Keep encoding and decoding in agreement with each other and with the PDF.
3. Verify each fix with the smallest test that reproduces it, preferably using byte sequences taken from the PDF.
4. In byte-level tests, build expected arrays from the protocol constants and put the literal hex bytes in a comment.
5. Update the parity checklist when a gap is closed or found.

## Output style

- Keep notes short and technical.
- Cite the protocol section or C function you relied on.
- Report uncertainty explicitly when the evidence is incomplete.
