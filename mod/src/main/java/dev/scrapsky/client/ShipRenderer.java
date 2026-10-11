package dev.scrapsky.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.scrapsky.ship.ShipEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;

/** Draws a flying ship block by block, turned to its heading. */
public class ShipRenderer extends EntityRenderer<ShipEntity> {
    public ShipRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        this.shadowRadius = 0.0f;
    }

    @Override
    public void render(ShipEntity ship, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        float heading = ship.getViewYRot(partialTick);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-heading));
        for (ShipEntity.ShipBlock b : ship.blocks()) {
            pose.pushPose();
            pose.translate(b.rel().getX() - 0.5, b.rel().getY() - ship.minY(), b.rel().getZ() - 0.5);
            blocks.renderSingleBlock(b.state(), pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
        pose.popPose();
        super.render(ship, yaw, partialTick, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(ShipEntity ship) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
