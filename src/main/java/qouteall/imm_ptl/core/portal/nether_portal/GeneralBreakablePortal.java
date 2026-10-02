package qouteall.imm_ptl.core.portal.nether_portal;

import qouteall.imm_ptl.core.McHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

public class GeneralBreakablePortal extends BreakablePortalEntity {
    
    public static final EntityType<GeneralBreakablePortal> ENTITY_TYPE =
        createPortalEntityType(GeneralBreakablePortal::new, McHelper.newResourceLocation("immersive_portals", "general_breakable_portal"));
    
    public GeneralBreakablePortal(EntityType<?> entityType, Level world) {
        super(entityType, world);
    }
    
    @Override
    protected boolean isPortalIntactOnThisSide() {
        boolean areaIntact = blockPortalShape.area.stream()
            .allMatch(blockPos ->
                level().getBlockState(blockPos).getBlock() == PortalPlaceholderBlock.instance
            );
        boolean frameIntact = blockPortalShape.frameAreaWithoutCorner.stream()
            .allMatch(blockPos -> !level().isEmptyBlock(blockPos));
        return areaIntact && frameIntact;
    }
    
    @Override
    protected void addSoundAndParticle() {
    
    }
}
