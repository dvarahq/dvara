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
package com.dvarahq.core.pii;

/**
 * Types of PII/PHI entities that can be detected in text.
 */
public enum PiiEntityType {
    EMAIL,
    PHONE_NUMBER,
    SSN,
    CREDIT_CARD,
    DATE_OF_BIRTH,
    IP_ADDRESS,
    PASSPORT_NUMBER,
    DRIVERS_LICENSE,
    IBAN,
    PERSON_NAME,
    MEDICAL_RECORD_NUMBER,
    DEA_NUMBER,
    NPI_NUMBER,
    /** India Aadhaar — 12 digits with a Verhoeff check digit. */
    AADHAAR,
    /** India PAN — {@code [A-Z]{5}[0-9]{4}[A-Z]}, format/structural validation. */
    PAN,
    CUSTOM
}