<!-- SPDX-License-Identifier: Apache-2.0 -->
# M3 generated evidence and Apache RAT scope — 2026-10-19

This is an additive build-verification repair. It does not change Java behavior,
API contracts, library source files, or frozen receipt bytes.

## Failure reproduced in CI

The focused `exact-java21-relocation` GitHub Actions job
`117883760354` reported 37 `UNAPPROVED` paths from the Apache RAT
license audit. The same job continued with the explicitly RAT-skipped
Java 21 test lane and reported **116 tests, zero failures/errors/skips,
BUILD SUCCESS**. These are separate gates: Java pass does not imply
RAT pass.

The reported RAT paths are limited to machine-generated qualification
receipts, frozen run logs, non-source metadata, and original context in:

- `m3/package-first/apache-circular-20261018/`
- `m3/package-import-rename-20261018/`
- `m3/stream-interop/`
- `m3/workspaces/long-path-input-budget-20261018/`
- `tools/m3scale/README.md`

The original bytes remain unchanged. The POM's build and reporting RAT
configurations carry matching narrow path exemptions. Neither
`src/main/**` nor `src/test/**` is exempted; Maven POMs and runtime
license files remain under normal RAT governance.

## Required verification before merging dependent source PRs

Run the full `mvn -B -ntp apache-rat:check` gate, and run the
`exact-java21-relocation` job with its Java 21 original/differential
tests. Confirm the 37 receipts are exempted, no new unapproved source
files are introduced, and 116 Java tests still pass.

A new upstream source or code file under an exempt path would require
a separate license review. Do not treat the path exclusion as
authorization to copy nonpermissive donor code or to change the
licensing of third-party material.

## Source preservation

This repair is an additive, two-parent PR to the fork's `master`.
Neither the Apache source owner nor any frozen evidence file is
overwritten or deleted. Re-evaluate the pending array-storage PR
after the audit gate passes.
