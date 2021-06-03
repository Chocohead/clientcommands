package net.earthcomputer.clientcommands.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.earthcomputer.clientcommands.render.RenderQueue;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {
	@Unique
	private static final boolean DO_CLEAR = !Boolean.getBoolean("clientcommands.skipclear");
	@Unique
	private static final boolean DO_TRANSLATE = !Boolean.getBoolean("clientcommands.skiptranslate");
	@Unique
	private static final boolean DO_DEPTH = !Boolean.getBoolean("clientcommands.skipdepth");
	@Unique
	private static final boolean DO_RENDER = !Boolean.getBoolean("clientcommands.skiprender");
    @Shadow @Final private BufferBuilderStorage buffers;
    @Shadow @Final private Camera camera;

    @Inject(method = "renderWorld", at = @At(value = "INVOKE_STRING", target = "Lnet/minecraft/util/profiler/Profiler;swap(Ljava/lang/String;)V", args = {"ldc=hand"}))
    private void renderWorldHand(float delta, long time, MatrixStack matrixStack, CallbackInfo ci) {
        matrixStack.push();

        // Render lines through everything
        // TODO: is this the best approach to render through blocks?
        if (DO_CLEAR) RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);

        if (DO_TRANSLATE) {
	        Vec3d cameraPos = camera.getPos();
	        matrixStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        }
        if (DO_DEPTH) RenderSystem.disableDepthTest();
        if (DO_RENDER) RenderQueue.render(RenderQueue.Layer.ON_TOP, matrixStack, buffers.getEntityVertexConsumers(), delta);
        if (DO_DEPTH) RenderSystem.enableDepthTest();

        matrixStack.pop();
    }
}
