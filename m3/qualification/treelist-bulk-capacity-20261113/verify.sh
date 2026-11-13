#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
# https://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "$0")/../../.." && pwd)
OUT=$(mktemp -d)
trap 'rm -rf -- "$OUT"' EXIT
SRC="$ROOT/src/main/java/com/m3/collections/apache"
TEST="$ROOT/src/test/java/com/m3/collections/apache/list/TreeListBulkCapacityRegression.java"
javac --release 21 -proc:none -Xlint:all -Werror -d "$OUT" \
  "$SRC/OrderedIterator.java" "$SRC/list/TreeList.java" "$TEST"
MAIN=com.m3.collections.apache.list.TreeListBulkCapacityRegression
java -cp "$OUT" "$MAIN" all
java -Xint -cp "$OUT" "$MAIN" contract
java -Xmx32m -cp "$OUT" "$MAIN" oom
if [[ "${1:-}" == allocation ]]; then
  javac --release 21 -proc:none -Xlint:all -Werror -cp "$OUT" -d "$OUT" \
    "$ROOT/m3/qualification/treelist-bulk-capacity-20261113/TreeListAllocationProbe.java"
  java -Xms128m -Xmx128m -cp "$OUT" TreeListAllocationProbe
fi
