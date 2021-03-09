/*
 * Copyright 2014 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.manualLabor.components;

import org.terasology.engine.entitySystem.Component;

/**
 * Add this to a substance so that when a tool is created, its durability is multiplied based on how much of the substance is present.  It stacks multiplicatively
 */
public class MultiplyToolDurabilityComponent implements Component, ToolModificationDescription {
    public float multiplyPerSubstanceAmount = 1f;

    @Override
    public String getDescription() {
        String upDown = multiplyPerSubstanceAmount < 1f ? "Decreases" : "Increases";

        return String.format(upDown + " tool durability by %.1f%% for every 10 units of material", Math.abs((Math.pow(multiplyPerSubstanceAmount, 10) - 1f) * 100));
    }
}
