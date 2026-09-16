/*
 * Copyright 2026 DVARA Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dvarahq.autoconfigure.config.yaml.repo;


/**
 * The one place the file store's write refusal is worded.
 *
 * <p>Throwing rather than quietly doing nothing is deliberate: returning the argument, or a
 * {@code false}, would let an admin path
 * appear to succeed while changing nothing — and here it would be worse, because the caller's next
 * restart would silently revert whatever they believed they had saved.
 */
final class YamlReadOnly {

    private YamlReadOnly() {}

    static UnsupportedOperationException on(String operation) {
        return new UnsupportedOperationException(operation + " is not available on this process: "
                + "configuration is read from gateway.yaml, which this process treats as read-only. "
                + "Edit the file and restart.");
    }

}