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

import com.dvarahq.core.prompt.PromptTemplate;
import com.dvarahq.core.prompt.PromptTemplateRepository;
import com.dvarahq.core.prompt.PromptTemplateVersion;

import java.util.List;
import java.util.Optional;

/**
 * Prompt templates, served from {@code gateway.yaml}.
 *
 * <p>Read by {@code DefaultPromptTemplateResolver} when a request carries
 * {@code metadata.prompt_template_id}, so a template in the file is one a caller can name.
 *
 * <p>Version history is refused rather than returned empty: a file has no history, and answering
 * "no versions" to a rollback surface is a different claim from answering "not here".
 */
public class YamlPromptTemplateRepository implements PromptTemplateRepository {

    private final YamlConfigStore store;

    public YamlPromptTemplateRepository(YamlConfigStore store) {
        this.store = store;
    }

    @Override
    public Optional<PromptTemplate> findById(String id) {
        return Optional.ofNullable(store.templatesById().get(id));
    }

    @Override
    public List<PromptTemplate> findAll() {
        return store.templates();
    }

    /**
     * The workspace's own templates, and the platform-global ones.
     *
     * <p>A template with no {@code workspace:} applies everywhere, exactly as a
     * {@code workspace_id IS NULL} row does — returning only the scoped ones would hide every shared
     * prompt from every workspace.
     */
    @Override
    public List<PromptTemplate> findByWorkspaceId(String workspaceId) {
        return store.templates().stream()
                .filter(t -> t.getWorkspaceId() == null || t.getWorkspaceId().equals(workspaceId))
                .toList();
    }

    @Override
    public PromptTemplate save(PromptTemplate template) {
        throw YamlReadOnly.on("PromptTemplateRepository.save");
    }

    @Override
    public boolean deleteById(String id) {
        throw YamlReadOnly.on("PromptTemplateRepository.deleteById");
    }

    @Override
    public List<PromptTemplateVersion> getVersionHistory(String templateId) {
        throw YamlReadOnly.on("PromptTemplateRepository.getVersionHistory");
    }

    @Override
    public Optional<PromptTemplateVersion> getVersion(String templateId, int version) {
        throw YamlReadOnly.on("PromptTemplateRepository.getVersion");
    }
}