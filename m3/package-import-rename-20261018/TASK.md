<!-- SPDX-License-Identifier: Apache-2.0 -->
# Commons Collections package and import rename

Authorized delta: recursively relocate `org.apache.commons.collections4` to
`com.m3.collections.apache`, with class and method names unchanged. Baseline:
`6404dfdfe553097bb934e044034abcec5a4dbdd0`; isolated branch
`m3/package-import-rename-20261018`.

Java production/test package declarations, imports, qualified types and source
paths must come from the qualified real OpenRewrite ChangePackage runner.
Metadata is limited to the separately pinned replacement manifest. Preserve all
implementation bodies, API members, constants, upstream license/NOTICE headers,
Maven coordinates and unrelated dependencies. No original-Collections runtime
dependency, adapter implementation or test skipping is authorized. Exact existing
target collisions are accepted only after mapped-byte equality; differing output
refuses before mutation. Binary serialized fixtures remain byte-identical.
Historical old-package serialization is a test-fixture concern; test-only class
resolution may map the old descriptors without claiming original wire identity.

Gates: original complete Maven test baseline; qualified recipe application;
source/member/body and collision custody; relocated compile and complete tests;
module/archive package checks; fixed-point recipe replay; no remote writes.
