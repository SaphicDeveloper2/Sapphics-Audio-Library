package com.sapphic.sal.block;

import com.sapphic.sal.radio.RadioStation;
import com.sapphic.sal.radio.StationRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Abstract base class for radio blocks.
 * Extend this to create custom radio blocks that can play internet radio stations.
 * 
 * <p>Features:</p>
 * <ul>
 *   <li>On/Off state via POWERED property</li>
 *   <li>Configurable radio station</li>
 *   <li>Right-click to toggle power</li>
 *   <li>Redstone controllable (optional)</li>
 * </ul>
 * 
 * <h2>Example Usage:</h2>
 * <pre>{@code
 * public class MyRadioBlock extends AbstractRadioBlock {
 *     public MyRadioBlock(Settings settings) {
 *         super(settings);
 *     }
 *     
 *     @Override
 *     public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
 *         return new MyRadioBlockEntity(pos, state);
 *     }
 *     
 *     @Override
 *     public BlockEntityType<?> getBlockEntityType() {
 *         return MyBlockEntities.RADIO;
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractRadioBlock extends BlockWithEntity {
    
    /**
     * Property indicating if the radio is currently playing.
     * True = playing, False = off
     */
    public static final BooleanProperty POWERED = Properties.POWERED;
    
    /**
     * Creates a new radio block.
     * 
     * @param settings Block settings
     */
    public AbstractRadioBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(POWERED, false));
    }
    
    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }
    
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        // Check if placed next to a powered redstone signal
        boolean powered = ctx.getWorld().isReceivingRedstonePower(ctx.getBlockPos());
        return getDefaultState().with(POWERED, shouldStartPowered(ctx, powered));
    }
    
    /**
     * Determines if the radio should start powered when placed.
     * Override to customize placement behavior.
     * 
     * @param ctx Placement context
     * @param redstonePowered Whether the block is receiving redstone power
     * @return True if the radio should start playing
     */
    protected boolean shouldStartPowered(ItemPlacementContext ctx, boolean redstonePowered) {
        return redstonePowered;
    }
    
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!world.isClient) {
            // Toggle power state
            boolean newPowered = !state.get(POWERED);
            world.setBlockState(pos, state.with(POWERED, newPowered));
            
            // Notify block entity of state change
            BlockEntity be = world.getBlockEntity(pos);
            if (be instanceof AbstractRadioBlockEntity radio) {
                if (newPowered) {
                    radio.onTurnedOn();
                } else {
                    radio.onTurnedOff();
                }
            }
        }
        return ActionResult.SUCCESS;
    }
    
    @Override
    protected void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (!world.isClient && isRedstoneControllable()) {
            boolean powered = world.isReceivingRedstonePower(pos);
            if (powered != state.get(POWERED)) {
                world.setBlockState(pos, state.with(POWERED, powered));
                
                BlockEntity be = world.getBlockEntity(pos);
                if (be instanceof AbstractRadioBlockEntity radio) {
                    if (powered) {
                        radio.onTurnedOn();
                    } else {
                        radio.onTurnedOff();
                    }
                }
            }
        }
    }
    
    /**
     * Whether this radio block responds to redstone signals.
     * Override to disable redstone control.
     * 
     * @return True if redstone can control this radio (default: true)
     */
    protected boolean isRedstoneControllable() {
        return true;
    }
    
    /**
     * Gets the default station for this radio block.
     * Override to use a different default station.
     * 
     * @return The default radio station (UpBeat by default)
     */
    public RadioStation getDefaultStation() {
        return StationRegistry.getUpBeat();
    }
    
    /**
     * Gets the default volume for this radio block.
     * Override to change the default volume.
     * 
     * @return Volume level (0.0 to 1.0, default: 0.8)
     */
    public float getDefaultVolume() {
        return 0.8f;
    }
    
    /**
     * Gets the hearing distance for this radio block.
     * Override to change how far the radio can be heard.
     * 
     * @return Maximum hearing distance in blocks (default: 32)
     */
    public float getHearingDistance() {
        return 32.0f;
    }
    
    @Override
    public abstract BlockEntity createBlockEntity(BlockPos pos, BlockState state);
    
    /**
     * Gets the block entity type for this radio block.
     * Must be implemented by subclasses.
     * 
     * @return The block entity type
     */
    public abstract BlockEntityType<?> getBlockEntityType();
    
    @SuppressWarnings("unchecked")
    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        if (world.isClient && type == getBlockEntityType()) {
            return (BlockEntityTicker<T>) (BlockEntityTicker<AbstractRadioBlockEntity>) AbstractRadioBlockEntity::clientTick;
        }
        return null;
    }
}
