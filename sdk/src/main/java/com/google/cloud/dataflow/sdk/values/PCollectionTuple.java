/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.values;

import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.transforms.AppliedPTransform;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.util.WindowingStrategy;
import com.google.cloud.dataflow.sdk.values.PCollection.IsBounded;
import com.google.common.collect.ImmutableMap;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code PCollectionTuple} is an immutable tuple of
 * heterogeneously-typed {@link PCollection}s, "keyed" by
 * {@link TupleTag}s.  A PCollectionTuple can be used as the input or
 * output of a
 * {@link com.google.cloud.dataflow.sdk.transforms.PTransform} taking
 * or producing multiple PCollection inputs or outputs that can be of
 * different types, for instance a
 * {@link com.google.cloud.dataflow.sdk.transforms.ParDo} with side
 * outputs.
 *
 * <p> A {@code PCollectionTuple} can be created and accessed like follows:
 * <pre> {@code
 * PCollection<String> pc1 = ...;
 * PCollection<Integer> pc2 = ...;
 * PCollection<Iterable<String>> pc3 = ...;
 *
 * // Create TupleTags for each of the PCollections to put in the
 * // PCollectionTuple (the type of the TupleTag enables tracking the
 * // static type of each of the PCollections in the PCollectionTuple):
 * TupleTag<String> tag1 = new TupleTag<>();
 * TupleTag<Integer> tag2 = new TupleTag<>();
 * TupleTag<Iterable<String>> tag3 = new TupleTag<>();
 *
 * // Create a PCollectionTuple with three PCollections:
 * PCollectionTuple pcs =
 *     PCollectionTuple.of(tag1, pc1)
 *                     .and(tag2, pc2)
 *                     .and(tag3, pc3);
 *
 * // Create an empty PCollectionTuple:
 * Pipeline p = ...;
 * PCollectionTuple pcs2 = PCollectionTuple.empty(p);
 *
 * // Get PCollections out of a PCollectionTuple, using the same tags
 * // that were used to put them in:
 * PCollection<Integer> pcX = pcs.get(tag2);
 * PCollection<String> pcY = pcs.get(tag1);
 * PCollection<Iterable<String>> pcZ = pcs.get(tag3);
 *
 * // Get a map of all PCollections in a PCollectionTuple:
 * Map<TupleTag<?>, PCollection<?>> allPcs = pcs.getAll();
 * } </pre>
 */
public class PCollectionTuple implements PInput, POutput {
  /**
   * Returns an empty {@code PCollectionTuple} that is part of the given {@link Pipeline}.
   *
   * <p> A {@link PCollectionTuple} containing additional elements can be created by calling
   * {@link #and} on the result.
   */
  public static PCollectionTuple empty(Pipeline pipeline) {
    return new PCollectionTuple(pipeline);
  }

  /**
   * Returns a singleton {@link PCollectionTuple} containing the given
   * {@link PCollection} keyed by the given {@link TupleTag}.
   *
   * <p> A {@code PCollectionTuple} containing additional elements can be created by calling
   * {@link #and} on the result.
   */
  public static <T> PCollectionTuple of(TupleTag<T> tag, PCollection<T> pc) {
    return empty(pc.getPipeline()).and(tag, pc);
  }

  /**
   * Returns a new {@link PCollectionTuple} that has each {@link PCollection} and
   * {@link TupleTag} of this {@link PCollectionTuple} plus the given {@link PCollection}
   * associated with the given {@link TupleTag}.
   *
   * <p> The given {@link TupleTag} should not already be mapped to a
   * {@link PCollection} in this {@link PCollectionTuple}.
   *
   * <p> Each {@link PCollection} in the resulting {@link PCollectionTuple} must be
   * part of the same {@link Pipeline}.
   */
  public <T> PCollectionTuple and(TupleTag<T> tag, PCollection<T> pc) {
    if (pc.getPipeline() != pipeline) {
      throw new IllegalArgumentException(
          "PCollections come from different Pipelines");
    }

    // The TypeDescriptor<T> in tag will often have good
    // reflective information about T
    pc.setTypeDescriptorInternal(tag.getTypeDescriptor());
    return new PCollectionTuple(pipeline,
        new ImmutableMap.Builder<TupleTag<?>, PCollection<?>>()
            .putAll(pcollectionMap)
            .put(tag, pc)
            .build());
  }

  /**
   * Returns whether this {@link PCollectionTuple} contains a {@link PCollection} with
   * the given tag.
   */
  public <T> boolean has(TupleTag<T> tag) {
    return pcollectionMap.containsKey(tag);
  }

  /**
   * Returns the {@link PCollection} associated with the given {@link TupleTag}
   * in this {@link PCollectionTuple}. Throws {@link IllegalArgumentException} if there is no
   * such {@link PCollection}, i.e., {@code !has(tag)}.
   */
  public <T> PCollection<T> get(TupleTag<T> tag) {
    @SuppressWarnings("unchecked")
    PCollection<T> pcollection = (PCollection<T>) pcollectionMap.get(tag);
    if (pcollection == null) {
      throw new IllegalArgumentException(
          "TupleTag not found in this PCollectionTuple tuple");
    }
    return pcollection;
  }

  /**
   * Returns an immutable Map from {@link TupleTag} to corresponding
   * {@link PCollection}, for all the members of this {@link PCollectionTuple}.
   */
  public Map<TupleTag<?>, PCollection<?>> getAll() {
    return pcollectionMap;
  }

  /**
   * Like {@link #apply(String, PTransform)} but defaulting to the name
   * of the {@link PTransform}.
   */
  public <OutputT extends POutput> OutputT apply(
      PTransform<PCollectionTuple, OutputT> t) {
    return Pipeline.applyTransform(this, t);
  }

  /**
   * Applies the given {@code PTransform} to this input {@code PCollectionTuple},
   * using {@code name} to identify this specific application of the transform.
   * This name is used in various places, including the monitoring UI, logging,
   * and to stably identify this application node in the job graph.
   */
  public <OutputT extends POutput> OutputT apply(
      String name, PTransform<PCollectionTuple, OutputT> t) {
    return Pipeline.applyTransform(name, this, t);
  }


  /////////////////////////////////////////////////////////////////////////////
  // Internal details below here.

  Pipeline pipeline;
  final Map<TupleTag<?>, PCollection<?>> pcollectionMap;

  PCollectionTuple(Pipeline pipeline) {
    this(pipeline, new LinkedHashMap<TupleTag<?>, PCollection<?>>());
  }

  PCollectionTuple(Pipeline pipeline,
                   Map<TupleTag<?>, PCollection<?>> pcollectionMap) {
    this.pipeline = pipeline;
    this.pcollectionMap = Collections.unmodifiableMap(pcollectionMap);
  }

  /**
   * Returns a {@link PCollectionTuple} with each of the given tags mapping to a new
   * output {@link PCollection}.
   *
   * <p> For use by primitive transformations only.
   */
  public static PCollectionTuple ofPrimitiveOutputsInternal(
      Pipeline pipeline,
      TupleTagList outputTags,
      WindowingStrategy<?, ?> windowingStrategy,
      IsBounded isBounded) {
    Map<TupleTag<?>, PCollection<?>> pcollectionMap = new LinkedHashMap<>();
    for (TupleTag<?> outputTag : outputTags.tupleTags) {
      if (pcollectionMap.containsKey(outputTag)) {
        throw new IllegalArgumentException(
            "TupleTag already present in this tuple");
      }

      // In fact, `token` and `outputCollection` should have
      // types TypeDescriptor<T> and PCollection<T> for some
      // unknown T. It is safe to create `outputCollection`
      // with type PCollection<Object> because it has the same
      // erasure as the correct type. When a transform adds
      // elements to `outputCollection` they will be of type T.
      @SuppressWarnings("unchecked")
      TypeDescriptor<Object> token = (TypeDescriptor<Object>) outputTag.getTypeDescriptor();
      PCollection<Object> outputCollection = PCollection
          .createPrimitiveOutputInternal(pipeline, windowingStrategy, isBounded)
          .setTypeDescriptorInternal(token);

      pcollectionMap.put(outputTag, outputCollection);
    }
    return new PCollectionTuple(null, pcollectionMap);
  }

  @Override
  public Pipeline getPipeline() {
    return pipeline;
  }

  @Override
  public Collection<? extends PValue> expand() {
    return pcollectionMap.values();
  }

  @Override
  public void recordAsOutput(AppliedPTransform<?, ?, ?> transform) {
    int i = 0;
    for (Map.Entry<TupleTag<?>, PCollection<?>> entry
             : pcollectionMap.entrySet()) {
      TupleTag<?> tag = entry.getKey();
      PCollection<?> pc = entry.getValue();
      pc.recordAsOutput(transform, tag.getOutName(i));
      i++;
    }
  }

  @Override
  public void finishSpecifying() {
    for (PCollection<?> pc : pcollectionMap.values()) {
      pc.finishSpecifying();
    }
  }

  @Override
  public void finishSpecifyingOutput() {
    for (PCollection<?> pc : pcollectionMap.values()) {
      pc.finishSpecifyingOutput();
    }
  }
}
