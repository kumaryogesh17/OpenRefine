/*******************************************************************************
 * MIT License
 *
 * Copyright (c) 2018 Antonin Delpeuch
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 ******************************************************************************/

package org.openrefine.wikidata.qa;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.openrefine.wikidata.manifests.Manifest;
import org.openrefine.wikidata.qa.scrutinizers.CalendarScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.CommonDescriptionScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.ConflictsWithScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.DifferenceWithinRangeScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.DistinctValuesScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.EditScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.EnglishDescriptionScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.EntityTypeScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.FormatScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.InverseConstraintScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.ItemRequiresScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.MultiValueScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.NewEntityScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.NoEditsMadeScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.QualifierCompatibilityScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.QuantityScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.RestrictedPositionScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.RestrictedValuesScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.SelfReferentialScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.SingleValueScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.UnsourcedScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.UseAsQualifierScrutinizer;
import org.openrefine.wikidata.qa.scrutinizers.WhitespaceScrutinizer;
import org.openrefine.wikidata.schema.WikibaseSchema;
import org.openrefine.wikidata.updates.EntityEdit;
import org.openrefine.wikidata.updates.scheduler.ImpossibleSchedulingException;
import org.openrefine.wikidata.updates.scheduler.WikibaseAPIUpdateScheduler;
import org.openrefine.wikidata.utils.EntityCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.wdtk.datamodel.interfaces.EntityIdValue;
import org.wikidata.wdtk.datamodel.interfaces.PropertyIdValue;

/**
 * Runs a collection of edit scrutinizers on an edit batch.
 * 
 * @author Antonin Delpeuch
 */
public class EditInspector {

    private static final Logger logger = LoggerFactory.getLogger(EditInspector.class);

    Map<String, EditScrutinizer> scrutinizers;
    private QAWarningStore warningStore;
    private ConstraintFetcher fetcher;
    private Manifest manifest;
    private EntityCache entityCache;

    public EditInspector(QAWarningStore warningStore, Manifest manifest) {
        this.scrutinizers = new HashMap<>();
        this.warningStore = warningStore;
        this.manifest = manifest;

        String propertyConstraintPid = manifest.getConstraintsRelatedId("property_constraint_pid");
        if (propertyConstraintPid != null) {
            entityCache = EntityCache.getEntityCache(manifest.getSiteIri(), manifest.getMediaWikiApiEndpoint());
            this.fetcher = new ConstraintFetcher(entityCache, propertyConstraintPid);
        }

        // Register all known scrutinizers here
        register(new NewEntityScrutinizer());
        register(new FormatScrutinizer());
        register(new InverseConstraintScrutinizer());
        register(new SelfReferentialScrutinizer());
        register(new UnsourcedScrutinizer());
        register(new RestrictedPositionScrutinizer());
        register(new QualifierCompatibilityScrutinizer());
        register(new SingleValueScrutinizer());
        register(new DistinctValuesScrutinizer());
        register(new NoEditsMadeScrutinizer());
        register(new WhitespaceScrutinizer());
        register(new QuantityScrutinizer());
        register(new RestrictedValuesScrutinizer());
        register(new EntityTypeScrutinizer());
        register(new CalendarScrutinizer());
        register(new CommonDescriptionScrutinizer());
        register(new EnglishDescriptionScrutinizer());
        register(new MultiValueScrutinizer());
        register(new DifferenceWithinRangeScrutinizer());
        register(new ConflictsWithScrutinizer());
        register(new ItemRequiresScrutinizer());
        register(new UseAsQualifierScrutinizer());
    }

    /**
     * Adds a new scrutinizer to the inspector.
     *
     * If any necessary dependency is missing, the scrutinizer will not be added.
     * 
     * @param scrutinizer
     */
    public void register(EditScrutinizer scrutinizer) {
        scrutinizer.setStore(warningStore);
        scrutinizer.setFetcher(fetcher);
        scrutinizer.setManifest(manifest);
        if (scrutinizer.prepareDependencies()) {
            String key = scrutinizer.getClass().getName();
            scrutinizers.put(key, scrutinizer);
        } else {
            logger.debug("scrutinizer [" + scrutinizer.getClass().getSimpleName() + "] is skipped " +
                    "due to missing necessary constraint configurations in the Wikibase manifest");
        }
    }

    /**
     * Inspect a batch of edits with the registered scrutinizers
     * 
     * @param editBatch
     */
    public void inspect(List<EntityEdit> editBatch, WikibaseSchema schema) throws ExecutionException {
        // First, schedule them with some scheduler,
        // so that all newly created entities appear in the batch
        SchemaPropertyExtractor fetcher = new SchemaPropertyExtractor();
        Set<PropertyIdValue> properties = fetcher.getAllProperties(schema);
        if (entityCache != null) {
            // Prefetch property documents in one API call rather than requesting them one by one.
            entityCache.getMultipleDocuments(properties.stream().collect(Collectors.toList()));
        }
        WikibaseAPIUpdateScheduler scheduler = new WikibaseAPIUpdateScheduler();
        try {
            editBatch = scheduler.schedule(editBatch);
        } catch (ImpossibleSchedulingException e) {
            throw new ExecutionException(e);
        }

        Map<EntityIdValue, EntityEdit> updates = EntityEdit.groupBySubject(editBatch);
        List<EntityEdit> mergedUpdates = updates.values().stream().collect(Collectors.toList());

        for (EditScrutinizer scrutinizer : scrutinizers.values()) {
            scrutinizer.batchIsBeginning();
        }

        for (EntityEdit update : mergedUpdates) {
            if (!update.isNull()) {
                for (EditScrutinizer scrutinizer : scrutinizers.values()) {
                    scrutinizer.scrutinize(update);
                }
            }
        }

        for (EditScrutinizer scrutinizer : scrutinizers.values()) {
            scrutinizer.batchIsFinished();
        }

        if (warningStore.getNbWarnings() == 0) {
            QAWarning warning = new QAWarning("no-issue-detected", null, QAWarning.Severity.INFO, 0);
            warning.setFacetable(false);
            warningStore.addWarning(warning);
        }
    }
}
