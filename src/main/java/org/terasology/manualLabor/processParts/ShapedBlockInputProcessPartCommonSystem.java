/*
 * Copyright 2016 MovingBlocks
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
package org.terasology.manualLabor.processParts;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.assets.ResourceUrn;
import org.terasology.engine.entitySystem.entity.EntityBuilder;
import org.terasology.engine.entitySystem.entity.EntityManager;
import org.terasology.engine.entitySystem.entity.EntityRef;
import org.terasology.engine.entitySystem.event.ReceiveEvent;
import org.terasology.engine.entitySystem.systems.BaseComponentSystem;
import org.terasology.engine.entitySystem.systems.RegisterSystem;
import org.terasology.module.inventory.systems.InventoryManager;
import org.terasology.module.inventory.systems.InventoryUtils;
import org.terasology.engine.registry.In;
import org.terasology.engine.world.block.BlockManager;
import org.terasology.engine.world.block.BlockUri;
import org.terasology.engine.world.block.items.BlockItemComponent;
import org.terasology.engine.world.block.items.BlockItemFactory;
import org.terasology.workstation.process.WorkstationInventoryUtils;
import org.terasology.workstation.process.inventory.InventoryInputItemsComponent;
import org.terasology.workstation.process.inventory.InventoryInputProcessPartCommonSystem;
import org.terasology.workstation.process.inventory.InventoryInputProcessPartSlotAmountsComponent;
import org.terasology.workstation.process.inventory.InventoryProcessPartUtils;
import org.terasology.workstation.processPart.ProcessEntityIsInvalidEvent;
import org.terasology.workstation.processPart.ProcessEntityIsInvalidToStartEvent;
import org.terasology.workstation.processPart.ProcessEntityStartExecutionEvent;
import org.terasology.workstation.processPart.inventory.ProcessEntityIsInvalidForInventoryItemEvent;
import org.terasology.workstation.processPart.metadata.ProcessEntityGetInputDescriptionEvent;
import org.terasology.workstation.system.WorkstationRegistry;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RegisterSystem
public class ShapedBlockInputProcessPartCommonSystem extends BaseComponentSystem {
    private static final Logger logger = LoggerFactory.getLogger(ShapedBlockInputProcessPartCommonSystem.class);
    @In
    InventoryManager inventoryManager;
    @In
    WorkstationRegistry workstationRegistry;
    @In
    BlockManager blockManager;
    @In
    EntityManager entityManager;

    @ReceiveEvent
    public void validateProcess(ProcessEntityIsInvalidEvent event, EntityRef processEntity,
                                ShapedBlockInputComponent shapedBlockInputComponent) {
        Set<EntityRef> items = null;
        try {
            items = createItems(shapedBlockInputComponent, false);
            if (items.size() == 0) {
                event.addError("No input items specified in " + this.getClass().getSimpleName());
            }
        } catch (Exception ex) {
            event.addError("Could not create input items in " + this.getClass().getSimpleName());
        } finally {
            if (items != null) {
                for (EntityRef item : items) {
                    item.destroy();
                }
            }
        }
    }

    @ReceiveEvent
    public void validateToStartExecution(ProcessEntityIsInvalidToStartEvent event, EntityRef processEntity,
                                         ShapedBlockInputComponent shapedBlockInputComponent) {
        Map<Predicate<EntityRef>, Integer> itemFilters = getInputItemsFilter(shapedBlockInputComponent);
        Map<Integer, Integer> slotAmounts = InventoryProcessPartUtils.findItems(event.getWorkstation(), InventoryInputProcessPartCommonSystem.WORKSTATIONINPUTCATEGORY, itemFilters, processEntity, event.getInstigator());
        if (slotAmounts != null) {
            processEntity.addComponent(new InventoryInputProcessPartSlotAmountsComponent(slotAmounts));
        } else {
            event.consume();
        }
    }


    @ReceiveEvent
    public void execute(ProcessEntityStartExecutionEvent event, EntityRef processEntity,
                        ShapedBlockInputComponent shapedBlockInputComponent) {
        InventoryInputProcessPartSlotAmountsComponent slotAmountsComponent = processEntity.getComponent(InventoryInputProcessPartSlotAmountsComponent.class);
        // this will be null if another process part has already consumed the items
        if (slotAmountsComponent != null) {
            InventoryInputItemsComponent inventoryInputItemsComponent = new InventoryInputItemsComponent();
            for (Map.Entry<Integer, Integer> slotAmount : slotAmountsComponent.slotAmounts.entrySet()) {
                EntityRef item = InventoryUtils.getItemAt(event.getWorkstation(), slotAmount.getKey());
                if (slotAmount.getValue() > InventoryUtils.getStackCount(item)) {
                    logger.error("Not enough items in the stack");
                }
                EntityRef removedItem = inventoryManager.removeItem(event.getWorkstation(), event.getInstigator(), item, false, slotAmount.getValue());
                inventoryInputItemsComponent.items.add(removedItem);
                if (removedItem == null) {
                    logger.error("Could not remove input item");
                }
            }

            // add the removed items to the process entity.  They will be destroyed along with the process entity eventually unless removed from the component.
            processEntity.addComponent(inventoryInputItemsComponent);
        }

        // remove the slot amounts from the process entity, no other InventoryInput should use it
        processEntity.removeComponent(InventoryInputProcessPartSlotAmountsComponent.class);
    }

    @ReceiveEvent
    public void validateInventoryItem(ProcessEntityIsInvalidForInventoryItemEvent event, EntityRef processEntity,
                                      ShapedBlockInputComponent shapedBlockInputComponent) {
        if (WorkstationInventoryUtils.getAssignedInputSlots(event.getWorkstation(), InventoryInputProcessPartCommonSystem.WORKSTATIONINPUTCATEGORY).contains(event.getSlotNo())
                && !Iterables.any(getInputItemsFilter(shapedBlockInputComponent).keySet(), x -> x.apply(event.getItem()))) {
            event.consume();
        }
    }

    @ReceiveEvent
    public void getInputDescriptions(ProcessEntityGetInputDescriptionEvent event, EntityRef processEntity,
                                     ShapedBlockInputComponent shapedBlockInputComponent) {
        Set<EntityRef> items = createItems(shapedBlockInputComponent, false);
        try {
            for (EntityRef item : items) {
                event.addInputDescription(InventoryProcessPartUtils.createProcessPartDescription(item));
            }
        } finally {
            for (EntityRef outputItem : items) {
                outputItem.destroy();
            }
        }
    }


    protected Map<Predicate<EntityRef>, Integer> getInputItemsFilter(ShapedBlockInputComponent shapedBlockInputComponent) {
        Map<Predicate<EntityRef>, Integer> output = Maps.newHashMap();
        output.put(input -> {
                    BlockItemComponent blockItemComponent = input.getComponent(BlockItemComponent.class);
                    if (blockItemComponent != null) {
                        Optional<ResourceUrn> shapeUrn = blockItemComponent.blockFamily.getURI().getShapeUrn();
                        if (shapeUrn.isPresent() && shapeUrn.get().equals(new ResourceUrn(shapedBlockInputComponent.shape))) {
                            return true;
                        }
                    }
                    return false;
                },
                shapedBlockInputComponent.amount);
        return output;
    }

    protected Set<EntityRef> createItems(ShapedBlockInputComponent shapedBlockInputComponent, boolean createPersistentEntities) {
        Set<EntityRef> output = Sets.newHashSet();
        BlockItemFactory blockFactory = new BlockItemFactory(entityManager);
        EntityBuilder builder = blockFactory.newBuilder(blockManager.getBlockFamily(new BlockUri(new ResourceUrn("ManualLabor:TempBlock"), new ResourceUrn(shapedBlockInputComponent.shape))), shapedBlockInputComponent.amount);
        builder.setPersistent(createPersistentEntities);
        output.add(builder.build());
        return output;
    }

}
