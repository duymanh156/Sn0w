package me.skitttyy.kami.impl.features.modules.combat;

import lombok.Getter;
import lombok.Setter;
import me.skitttyy.kami.api.event.eventbus.SubscribeEvent;
import me.skitttyy.kami.api.event.events.TickEvent;
import me.skitttyy.kami.api.feature.module.Module;
import me.skitttyy.kami.api.management.FriendManager;
import me.skitttyy.kami.api.utils.NullUtils;
import me.skitttyy.kami.api.utils.chat.ChatUtils;
import me.skitttyy.kami.api.utils.players.InventoryUtils;
import me.skitttyy.kami.api.utils.players.PlayerUtils;
import me.skitttyy.kami.api.utils.players.rotation.RotationUtils;
import me.skitttyy.kami.api.utils.world.BlockUtils;
import me.skitttyy.kami.api.utils.world.CrystalUtil;
import me.skitttyy.kami.api.value.Value;
import me.skitttyy.kami.api.value.builder.ValueBuilder;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PistonCrystal extends Module {
    
    private long lastPlaceTime = 0;
    private long lastAttackTime = 0;
    
    Value<Number> minDamage = new ValueBuilder<Number>()
            .withDescriptor("MinDamage")
            .withValue(7.0)
            .withRange(0d, 20d)
            .withPlaces(1)
            .register(this);
    
    Value<Number> maxLocalDamage = new ValueBuilder<Number>()
            .withDescriptor("MaxLocalDamage")
            .withValue(13.0)
            .withRange(0d, 36d)
            .withPlaces(1)
            .register(this);
    
    Value<Number> delay = new ValueBuilder<Number>()
            .withDescriptor("Delay")
            .withValue(4)
            .withRange(0, 20)
            .register(this);
    
    Value<Number> attackWait = new ValueBuilder<Number>()
            .withDescriptor("AttackWait")
            .withValue(2)
            .withRange(0, 10)
            .register(this);
    
    Value<Boolean> v1_12 = new ValueBuilder<Boolean>()
            .withDescriptor("1.12")
            .withValue(false)
            .register(this);
    
    Value<Number> targetRange = new ValueBuilder<Number>()
            .withDescriptor("TargetRange")
            .withValue(6.0)
            .withRange(1d, 12d)
            .register(this);
    
    Value<String> sort = new ValueBuilder<String>()
            .withDescriptor("Sort")
            .withValue("LOWEST_DISTANCE")
            .withModes("CLOSEST_ANGLE", "LOWEST_DISTANCE", "LOWEST_HEALTH")
            .register(this);
    
    Value<Boolean> inAirTarget = new ValueBuilder<Boolean>()
            .withDescriptor("InAirTarget")
            .withValue(true)
            .register(this);
    
    Value<Boolean> rotate = new ValueBuilder<Boolean>()
            .withDescriptor("Rotate")
            .withValue(true)
            .register(this);
    
    Value<Boolean> grim = new ValueBuilder<Boolean>()
            .withDescriptor("Grim")
            .withValue(true)
            .register(this);
    
    Value<Boolean> breakCrystal = new ValueBuilder<Boolean>()
            .withDescriptor("BreakCrystal")
            .withValue(true)
            .register(this);
    
    Value<Number> placeRange = new ValueBuilder<Number>()
            .withDescriptor("PlaceRange")
            .withValue(5.5)
            .withRange(1d, 8d)
            .register(this);
    
    Value<Number> breakRange = new ValueBuilder<Number>()
            .withDescriptor("BreakRange")
            .withValue(3.0)
            .withRange(1d, 6d)
            .register(this);
    
    Value<String> swapAction = new ValueBuilder<String>()
            .withDescriptor("SwapAction")
            .withValue("SCREEN")
            .withModes("SCREEN", "HOTBAR", "NONE")
            .register(this);
    
    Value<Boolean> safety = new ValueBuilder<Boolean>()
            .withDescriptor("Safety")
            .withValue(true)
            .register(this);
    
    Value<Boolean> selfExtrapolate = new ValueBuilder<Boolean>()
            .withDescriptor("SelfExtrapolate")
            .withValue(true)
            .register(this);
    
    Value<Number> extrapolationTicks = new ValueBuilder<Number>()
            .withDescriptor("ExtrapolationTicks")
            .withValue(3)
            .withRange(0, 10)
            .register(this);
    
    Value<Boolean> assumeBestArmor = new ValueBuilder<Boolean>()
            .withDescriptor("AssumeBestArmor")
            .withValue(true)
            .register(this);
    
    private PlayerEntity target;
    private PistonData currentData;
    private boolean didPiston;
    private boolean didRedstone;
    private boolean didCrystal;
    private int extrapolationOffset;
    
    public PistonCrystal() {
        super("PistonCrystal", Category.Combat);
    }
    
    @SubscribeEvent
    public void onUpdate(final TickEvent.PlayerTickEvent.Pre event) {
        if (NullUtils.nullCheck()) return;
        
        if (selfExtrapolate.getValue() && extrapolationOffset < extrapolationTicks.getValue().intValue()) {
            extrapolationOffset++;
        }
        
        target = getTarget();
        if (target == null) {
            resetState();
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastPlaceTime < delay.getValue().intValue() * 50L) return;
        
        if (currentData == null) {
            currentData = getBestPosition(target);
            if (currentData == null) return;
            
            if (currentData.damage < minDamage.getValue().doubleValue()) return;
            if (safety.getValue() && currentData.selfDamage > maxLocalDamage.getValue().doubleValue()) return;
        }
        
        if (!didPiston) {
            if (tryPlace(currentData.pistonPos, getPistonItem(), true)) {
                didPiston = true;
                lastPlaceTime = currentTime;
            }
        } else if (!didRedstone) {
            if (tryPlace(currentData.redstonePos, getRedstoneItem(), true)) {
                didRedstone = true;
                lastAttackTime = currentTime;
                lastPlaceTime = currentTime;
            }
        } else if (!didCrystal) {
            if (currentTime - lastAttackTime >= attackWait.getValue().intValue() * 50L) {
                if (tryPlace(currentData.crystalPos, Items.END_CRYSTAL, false)) {
                    didCrystal = true;
                    lastAttackTime = currentTime;
                    lastPlaceTime = currentTime;
                }
            }
        } else {
            if (breakCrystal.getValue() && currentTime - lastAttackTime >= attackWait.getValue().intValue() * 50L) {
                attackCrystal();
            }
            resetState();
            toggle();
            return;
        }
    }
    
    private boolean tryPlace(BlockPos pos, Item item, boolean needDirection) {
        // NONE mode check
        if (swapAction.getValue().equals("NONE")) {
            if (!mc.player.getMainHandStack().getItem().equals(item)) {
                return false;
            }
        }
        
        // Get slot
        int slot = -1;
        switch (swapAction.getValue()) {
            case "SCREEN":
                slot = getInventoryItemSlot(item);
                if (slot != -1) InventoryUtils.switchToSlotGhost(slot);
                break;
            case "HOTBAR":
                slot = InventoryUtils.getHotbarItemSlot(item);
                if (slot != -1) InventoryUtils.switchToSlot(slot);
                break;
            case "NONE":
                slot = mc.player.getInventory().selectedSlot;
                break;
        }
        
        if (slot == -1) return false;
        if (!mc.player.getMainHandStack().getItem().equals(item)) return false;
        
        // Get side
        Direction side = null;
        if (needDirection) {
            side = BlockUtils.getPlaceableSide(pos, grim.getValue());
            if (side == null) return false;
        } else {
            side = Direction.UP;
        }
        
        // Rotate
        if (rotate.getValue()) {
            if (needDirection) {
                RotationUtils.doRotate(pos, side);
            } else {
                RotationUtils.doRotate(pos);
            }
        }
        
        // Place
        int oldSlot = mc.player.getInventory().selectedSlot;
        boolean success = BlockUtils.placeBlock(pos, side, false, true);
        
        // Restore slot for HOTBAR mode
        if (swapAction.getValue().equals("HOTBAR") && oldSlot != mc.player.getInventory().selectedSlot) {
            InventoryUtils.switchToSlot(oldSlot);
        }
        
        return success;
    }
    
    private int getInventoryItemSlot(Item item) {
        for (int i = 0; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).getItem().equals(item)) {
                return i;
            }
        }
        return -1;
    }
    
    private Item getPistonItem() {
        if (InventoryUtils.getHotbarItemSlot(Items.PISTON) != -1) return Items.PISTON;
        if (InventoryUtils.getHotbarItemSlot(Items.STICKY_PISTON) != -1) return Items.STICKY_PISTON;
        return Items.PISTON;
    }
    
    private Item getRedstoneItem() {
        if (InventoryUtils.getHotbarItemSlot(Items.REDSTONE_TORCH) != -1) return Items.REDSTONE_TORCH;
        if (InventoryUtils.getHotbarItemSlot(Items.REDSTONE_BLOCK) != -1) return Items.REDSTONE_BLOCK;
        return Items.REDSTONE_TORCH;
    }
    
    private void attackCrystal() {
        EndCrystalEntity bestCrystal = null;
        double bestDist = breakRange.getValue().doubleValue();
        
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity crystal && crystal.isAlive()) {
                double dist = mc.player.distanceTo(crystal);
                if (dist <= bestDist && (bestCrystal == null || dist < bestDist)) {
                    bestDist = dist;
                    bestCrystal = crystal;
                }
            }
        }
        
        if (bestCrystal != null) {
            if (rotate.getValue()) {
                float[] rots = RotationUtils.getRotationsTo(mc.player.getEyePos(), bestCrystal.getPos());
                RotationUtils.setRotation(rots);
            }
            PlayerUtils.attackTarget(bestCrystal);
        }
    }
    
    private PistonData getBestPosition(PlayerEntity target) {
        List<PistonData> positions = new ArrayList<>();
        BlockPos targetPos = getTargetPosition(target);
        
        // Feet, body, head
        BlockPos[] levels = {targetPos, targetPos.up(), targetPos.up(2)};
        
        for (BlockPos level : levels) {
            for (Direction dir : Direction.values()) {
                if (!dir.getAxis().isHorizontal()) continue;
                
                BlockPos pistonPos = level.offset(dir);
                if (!canPlacePiston(pistonPos, dir)) continue;
                
                for (Direction redstoneDir : Direction.values()) {
                    if (redstoneDir == dir.getOpposite() || redstoneDir.getAxis().isVertical()) continue;
                    
                    BlockPos redstonePos = pistonPos.offset(redstoneDir);
                    if (!BlockUtils.canPlaceBlock(redstonePos, grim.getValue(), pistonPos)) continue;
                    if (!BlockUtils.isInRange(redstonePos, placeRange.getValue().floatValue())) continue;
                    
                    BlockPos pushedPos = level.offset(dir, 2);
                    BlockPos crystalPos = pushedPos.up();
                    
                    float damage = CrystalUtil.calculateDamage(target, crystalPos.toCenterPos(), false, false);
                    float selfDamage = CrystalUtil.calculateDamage(mc.player, crystalPos.toCenterPos(), false, false);
                    
                    if (assumeBestArmor.getValue()) {
                        damage *= 0.36f;
                        selfDamage *= 0.36f;
                    }
                    
                    float angle = getAngleTo(pistonPos);
                    positions.add(new PistonData(pistonPos, redstonePos, crystalPos, damage, selfDamage, angle, target.getHealth()));
                }
            }
        }
        
        if (positions.isEmpty()) return null;
        
        // Sort
        String sortMode = sort.getValue();
        switch (sortMode) {
            case "CLOSEST_ANGLE":
                positions.sort(Comparator.comparingDouble(p -> p.angle));
                break;
            case "LOWEST_HEALTH":
                positions.sort(Comparator.comparingDouble(p -> p.targetHealth));
                break;
            default:
                positions.sort(Comparator.comparingDouble(p -> mc.player.getPos().distanceTo(p.pistonPos.toCenterPos())));
                break;
        }
        
        // Filter
        positions.removeIf(p -> p.damage < minDamage.getValue().doubleValue());
        if (safety.getValue()) {
            positions.removeIf(p -> p.selfDamage > maxLocalDamage.getValue().doubleValue());
        }
        
        return positions.isEmpty() ? null : positions.get(0);
    }
    
    private BlockPos getTargetPosition(PlayerEntity target) {
        BlockPos pos = target.getBlockPos();
        
        if (selfExtrapolate.getValue() && extrapolationOffset > 0) {
            Vec3d velocity = target.getVelocity();
            if (Math.abs(velocity.x) > 0.1 || Math.abs(velocity.z) > 0.1) {
                int ticks = Math.min(extrapolationOffset, extrapolationTicks.getValue().intValue());
                int offsetX = (int) Math.round(velocity.x * ticks);
                int offsetZ = (int) Math.round(velocity.z * ticks);
                pos = new BlockPos(pos.getX() + offsetX, pos.getY(), pos.getZ() + offsetZ);
            }
        }
        
        return pos;
    }
    
    private float getAngleTo(BlockPos pos) {
        float[] rots = RotationUtils.getRotationsTo(mc.player.getEyePos(), pos.toCenterPos());
        float deltaYaw = Math.abs(MathHelper.wrapDegrees(rots[0] - mc.player.getYaw()));
        float deltaPitch = Math.abs(rots[1] - mc.player.getPitch());
        return deltaYaw + deltaPitch;
    }
    
    private boolean canPlacePiston(BlockPos pos, Direction dir) {
        if (!BlockUtils.canPlaceBlock(pos, grim.getValue())) return false;
        if (!BlockUtils.isInRange(pos, placeRange.getValue().floatValue())) return false;
        
        // Check 2 blocks behind for piston extension
        BlockPos behind = pos.offset(dir.getOpposite(), 2);
        return mc.world.getBlockState(behind).isAir();
    }
    
    private PlayerEntity getTarget() {
        PlayerEntity closest = null;
        double closestDist = targetRange.getValue().doubleValue();
        
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof PlayerEntity player && player != mc.player 
                && !FriendManager.INSTANCE.isFriend(player) && player.isAlive()
                && mc.player.distanceTo(player) <= closestDist) {
                
                if (inAirTarget.getValue() || player.isOnGround()) {
                    double dist = mc.player.distanceTo(player);
                    if (dist < closestDist) {
                        closestDist = dist;
                        closest = player;
                    }
                }
            }
        }
        return closest;
    }
    
    private boolean hasRequiredItems() {
        switch (swapAction.getValue()) {
            case "NONE":
                return true;
            case "HOTBAR":
                return InventoryUtils.getHotbarItemSlot(Items.PISTON) != -1 
                    || InventoryUtils.getHotbarItemSlot(Items.STICKY_PISTON) != -1;
            default:
                return getInventoryItemSlot(Items.PISTON) != -1 
                    || getInventoryItemSlot(Items.STICKY_PISTON) != -1;
        }
    }
    
    private void resetState() {
        currentData = null;
        didPiston = false;
        didRedstone = false;
        didCrystal = false;
        extrapolationOffset = 0;
    }
    
    @Override
    public void onEnable() {
        super.onEnable();
        if (NullUtils.nullCheck()) return;
        resetState();
        lastPlaceTime = System.currentTimeMillis();
        lastAttackTime = System.currentTimeMillis();
        
        if (!hasRequiredItems()) {
            ChatUtils.sendMessage("[PistonCrystal] Missing required items!");
            toggle();
        }
    }
    
    @Override
    public String getDescription() {
        return "PistonCrystal - Push target into crystal using pistons";
    }
    
    @Getter
    @Setter
    public static class PistonData {
        private final BlockPos pistonPos;
        private final BlockPos redstonePos;
        private final BlockPos crystalPos;
        private final float damage;
        private final float selfDamage;
        private final float angle;
        private final float targetHealth;
        
        public PistonData(BlockPos pistonPos, BlockPos redstonePos, BlockPos crystalPos,
                         float damage, float selfDamage, float angle, float targetHealth) {
            this.pistonPos = pistonPos;
            this.redstonePos = redstonePos;
            this.crystalPos = crystalPos;
            this.damage = damage;
            this.selfDamage = selfDamage;
            this.angle = angle;
            this.targetHealth = targetHealth;
        }
    }
}