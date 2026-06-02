package me.skitttyy.kami.impl.features.modules.combat;

import lombok.Getter;
import lombok.Setter;
import me.skitttyy.kami.api.event.eventbus.SubscribeEvent;
import me.skitttyy.kami.api.event.events.TickEvent;
import me.skitttyy.kami.api.feature.module.Module;
import me.skitttyy.kami.api.management.FriendManager;
import me.skitttyy.kami.api.utils.NullUtils;
import me.skitttyy.kami.api.utils.Timer;
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
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PistonCrystal extends Module {
    
    private final Timer timer = new Timer();
    private final Timer attackTimer = new Timer();
    
    // Settings
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
            .withAction(set -> timer.setDelay(set.getValue().intValue()))
            .register(this);
    
    Value<Number> attackWait = new ValueBuilder<Number>()
            .withDescriptor("AttackWait")
            .withValue(2)
            .withRange(0, 10)
            .withAction(set -> attackTimer.setDelay(set.getValue().intValue()))
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
    
    // State variables
    private PlayerEntity target;
    private PistonData currentData;
    private boolean didPiston = false;
    private boolean didRedstone = false;
    private boolean didCrystal = false;
    private BlockPos toPlace;
    private int slot;
    private int extrapolationOffset = 0;
    
    public PistonCrystal() {
        super("PistonCrystal", Category.Combat);
    }
    
    @SubscribeEvent
    public void onUpdate(final TickEvent.PlayerTickEvent.Pre event) {
        if (NullUtils.nullCheck()) return;
        
        // Update extrapolation
        if (selfExtrapolate.getValue() && extrapolationOffset < extrapolationTicks.getValue().intValue()) {
            extrapolationOffset++;
        }
        
        // Find target
        target = getTarget();
        if (target == null) {
            resetState();
            return;
        }
        
        // Check delay
        if (!timer.hasPassed(delay.getValue().intValue() * 50L)) return;
        
        // Check items
        if (!hasRequiredItems()) {
            ChatUtils.sendMessage("[PistonCrystal] Missing required items!");
            this.toggle();
            return;
        }
        
        // Get best position
        if (currentData == null) {
            currentData = getBestPosition(target);
            if (currentData == null) return;
            
            if (currentData.damage < minDamage.getValue().doubleValue()) return;
            if (safety.getValue() && currentData.selfDamage > maxLocalDamage.getValue().doubleValue()) return;
        }
        
        // Sequential placement logic
        if (!didPiston) {
            slot = getPistonSlot();
            toPlace = currentData.pistonPos;
        } else if (!didRedstone) {
            slot = getRedstoneSlot();
            toPlace = currentData.redstonePos;
        } else if (!didCrystal) {
            if (!attackTimer.hasPassed(attackWait.getValue().intValue() * 50L)) return;
            slot = InventoryUtils.getHotbarItemSlot(Items.END_CRYSTAL);
            toPlace = currentData.crystalPos;
        } else {
            if (breakCrystal.getValue() && attackTimer.hasPassed(attackWait.getValue().intValue() * 50L)) {
                attackCrystal();
            }
            resetState();
            this.toggle();
            return;
        }
        
        // Place block
        if (toPlace != null && slot != -1) {
            // Rotation for piston (giống AntiHolecamp - xoay cứng 0, 90, -90, 180)
            if (rotate.getValue() && !didPiston && currentData != null) {
                if (currentData.pistonDir == Direction.NORTH) {
                    RotationUtils.packetRotate(180, 0);
                } else if (currentData.pistonDir == Direction.SOUTH) {
                    RotationUtils.packetRotate(0, 0);
                } else if (currentData.pistonDir == Direction.WEST) {
                    RotationUtils.packetRotate(90, 0);
                } else if (currentData.pistonDir == Direction.EAST) {
                    RotationUtils.packetRotate(-90, 0);
                }
            } else if (rotate.getValue() && !didCrystal) {
                // Rotation for redstone and crystal
                if (didRedstone) {
                    RotationUtils.doRotate(toPlace);
                } else {
                    RotationUtils.doRotate(toPlace, grim.getValue());
                }
            } else if (rotate.getValue() && didCrystal) {
                RotationUtils.doRotate(toPlace);
            }
            
            int oldSlot = mc.player.getInventory().selectedSlot;
            
            switch (swapAction.getValue()) {
                case "HOTBAR" -> InventoryUtils.switchToSlot(slot);
                case "SCREEN" -> InventoryUtils.switchToSlotGhost(slot);
                default -> {}
            }
            
            Direction side = null;
            if (!didCrystal) {
                side = BlockUtils.getPlaceableSide(toPlace, grim.getValue());
            } else {
                side = Direction.UP;
            }
            
            if (side != null) {
                BlockUtils.placeBlock(toPlace, side, false, true);
            }
            
            if (swapAction.getValue().equals("HOTBAR") && oldSlot != mc.player.getInventory().selectedSlot) {
                InventoryUtils.switchToSlot(oldSlot);
            }
            
            // Update state
            if (!didPiston) {
                didPiston = true;
            } else if (!didRedstone) {
                didRedstone = true;
                attackTimer.reset();
            } else if (!didCrystal) {
                didCrystal = true;
                attackTimer.reset();
            }
            
            timer.reset();
            toPlace = null;
        }
    }
    
    private int getPistonSlot() {
        int slot = InventoryUtils.getHotbarItemSlot(Items.PISTON);
        if (slot == -1) slot = InventoryUtils.getHotbarItemSlot(Items.STICKY_PISTON);
        return slot;
    }
    
    private int getRedstoneSlot() {
        int slot = InventoryUtils.getHotbarItemSlot(Items.REDSTONE_TORCH);
        if (slot == -1) slot = InventoryUtils.getHotbarItemSlot(Items.REDSTONE_BLOCK);
        return slot;
    }
    
    private int getInventoryItemSlot(Item item) {
        for (int i = 0; i <= 35; i++) {
            if (mc.player.getInventory().getStack(i).getItem().equals(item)) {
                return i;
            }
        }
        return -1;
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
    
    private void attackCrystal() {
        for (Entity entity : mc.world.getEntities()) {
            if (entity instanceof EndCrystalEntity crystal && crystal.isAlive() 
                && mc.player.distanceTo(crystal) <= breakRange.getValue().doubleValue()) {
                if (rotate.getValue()) {
                    float[] rots = RotationUtils.getRotationsTo(mc.player.getEyePos(), crystal.getPos());
                    RotationUtils.setRotation(rots);
                }
                PlayerUtils.attackTarget(crystal);
                break;
            }
        }
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
    
    private PistonData getBestPosition(PlayerEntity target) {
        List<PistonData> positions = new ArrayList<>();
        BlockPos targetPos = getTargetPosition(target);
        
        // Check feet, body, head
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
                    positions.add(new PistonData(pistonPos, redstonePos, crystalPos, damage, selfDamage, angle, target.getHealth(), dir));
                }
            }
        }
        
        if (positions.isEmpty()) return null;
        
        // Sort
        switch (sort.getValue()) {
            case "CLOSEST_ANGLE":
                positions.sort(Comparator.comparingDouble(p -> p.angle));
                break;
            case "LOWEST_HEALTH":
                positions.sort(Comparator.comparingDouble(p -> p.targetHealth));
                break;
            default:
                positions.sort(Comparator.comparingDouble(p -> mc.player.distanceTo(p.pistonPos.toCenterPos())));
                break;
        }
        
        // Filter
        positions.removeIf(p -> p.damage < minDamage.getValue().doubleValue());
        if (safety.getValue()) {
            positions.removeIf(p -> p.selfDamage > maxLocalDamage.getValue().doubleValue());
        }
        
        return positions.isEmpty() ? null : positions.get(0);
    }
    
    private float getAngleTo(BlockPos pos) {
        float[] rots = RotationUtils.getRotationsTo(mc.player.getEyePos(), pos.toCenterPos());
        float deltaYaw = Math.abs(rots[0] - mc.player.getYaw());
        float deltaPitch = Math.abs(rots[1] - mc.player.getPitch());
        return deltaYaw + deltaPitch;
    }
    
    private BlockPos getTargetPosition(PlayerEntity target) {
        BlockPos pos = target.getBlockPos();
        
        if (selfExtrapolate.getValue() && extrapolationOffset > 0) {
            Vec3d velocity = target.getVelocity();
            if (velocity.length() > 0.1) {
                int ticks = Math.min(extrapolationOffset, extrapolationTicks.getValue().intValue());
                double offsetX = velocity.x * ticks;
                double offsetZ = velocity.z * ticks;
                pos = new BlockPos(
                    (int) Math.round(target.getX() + offsetX),
                    (int) target.getY(),
                    (int) Math.round(target.getZ() + offsetZ)
                );
            }
        }
        
        return pos;
    }
    
    private boolean canPlacePiston(BlockPos pos, Direction dir) {
        if (!BlockUtils.canPlaceBlock(pos, grim.getValue())) return false;
        if (!BlockUtils.isInRange(pos, placeRange.getValue().floatValue())) return false;
        
        BlockPos behind = pos.offset(dir.getOpposite(), 2);
        return mc.world.getBlockState(behind).isAir();
    }
    
    private void resetState() {
        currentData = null;
        didPiston = false;
        didRedstone = false;
        didCrystal = false;
        toPlace = null;
        extrapolationOffset = 0;
    }
    
    @Override
    public void onEnable() {
        super.onEnable();
        if (NullUtils.nullCheck()) return;
        resetState();
        timer.reset();
        attackTimer.reset();
        
        if (!hasRequiredItems()) {
            ChatUtils.sendMessage("[PistonCrystal] Missing required items!");
            this.toggle();
        }
    }
    
    @Override
    public String getDescription() {
        return "PistonCrystal - Push target into crystal using pistons at feet, body, and head levels";
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
        private final Direction pistonDir;
        
        public PistonData(BlockPos pistonPos, BlockPos redstonePos, BlockPos crystalPos,
                         float damage, float selfDamage, float angle, float targetHealth, Direction pistonDir) {
            this.pistonPos = pistonPos;
            this.redstonePos = redstonePos;
            this.crystalPos = crystalPos;
            this.damage = damage;
            this.selfDamage = selfDamage;
            this.angle = angle;
            this.targetHealth = targetHealth;
            this.pistonDir = pistonDir;
        }
    }
}