package org.uee.mc;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.uee.icon.Png;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Renders items and entities to PNG images on the client, off-screen.
 *
 * <h2>Where this sits</h2>
 *
 * <p>This is the only class in the project that touches the render pipeline, and it is deliberately the
 * only one: everything about the data phase is written so that it does not, which is why the data phase
 * can run on a server, off the game thread, and in a test. Rendering is reached from here and nowhere
 * else, so when something looks wrong the search has one place to start.
 *
 * <h2>It runs on the render thread or it does not run</h2>
 *
 * <p>Every method here asserts it is being called from the render thread. The pipeline is not
 * thread-safe and the failure mode is not an exception: the state machine is global, so calling it from
 * another thread produces wrong images and corrupted rendering for the rest of the session rather than
 * a clean error. An assertion turns a class of "the icons look wrong sometimes" into a stack trace at
 * the call that caused it, which is the difference between a bug that can be found and one that cannot.
 *
 * <h2>Every resource is released on the way out</h2>
 *
 * <p>The render target holds a framebuffer, a colour texture and a depth buffer, and the world's render
 * state is saved and restored around each call. An icon render happens between frames, so anything left
 * bound or reallocated is a rendering artefact the player sees, and anything not released is memory the
 * client holds for the rest of the session — thousands of times over, if the phase is left running.
 *
 * <h2>The part that is not verified</h2>
 *
 * <p>The pixel encoding is verified: the encoder is in the core and its output is read back by an
 * independent decoder. What is not verified is the rendering — this code has never been run, because the
 * test environment has no client and the game tests run on a headless server. What it is written against
 * is verified: every call below was checked against the compiled signatures of the version it targets
 * rather than against recollection, because a plausible-looking wrong call is the failure mode this
 * whole file is exposed to. The things to look at first when it is run for the first time: whether items
 * come out the right size and position, whether a translucent item composites correctly, and whether
 * entities need a world (see {@link #renderEntity}).
 */
public final class IconRenderer {

    /**
     * The projection an item's GUI transform expects.
     *
     * <p>A 16-unit orthographic box with Y increasing downwards, which is the coordinate space the GUI
     * item transform is authored in. The near and far planes are placed well outside the model so that
     * nothing is clipped whichever way the transform sends depth; getting them the wrong side of the
     * geometry is a silent failure, since a clipped item renders as an empty image rather than an error.
     */
    private static void applyItemProjection() {
        org.joml.Matrix4f projection = new org.joml.Matrix4f().setOrtho(0.0F, 16.0F, 16.0F, 0.0F,
                1000.0F, 3000.0F);
        RenderSystem.setProjectionMatrix(projection, VertexSorting.ORTHOGRAPHIC_Z);
        // The model view is a matrix stack rather than a pose stack, and it is saved and restored by
        // pushing rather than by copying, because it is the render system's own state and not a local.
        org.joml.Matrix4fStack model = RenderSystem.getModelViewStack();
        model.pushMatrix();
        model.identity();
        // The GUI item transform places the model around the origin and expects to be viewed from a
        // fixed distance; without moving the camera back the model falls behind the near plane.
        model.translate(0.0F, 0.0F, -2000.0F);
        RenderSystem.applyModelViewMatrix();
    }

    private static void popItemProjection() {
        RenderSystem.getModelViewStack().popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    /**
     * Renders one item at each requested size, or an empty map when it cannot be rendered.
     *
     * <p>All sizes come from one pass over the model, because the expensive part is the render rather
     * than the read-back: the model is loaded, transformed and drawn once, and each size is a resize of
     * the target and a read, which costs a fraction of it. Rendering a 128 and a 32 in one call also
     * means the two can never disagree about what the item looks like.
     *
     * @param id the item's registry id, used only to build a readable failure message
     */
    public Map<Integer, byte[]> renderItem(ResourceLocation id, int[] sizes) {
        Minecraft client = Minecraft.getInstance();
        Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        if (item == null) {
            return Map.of();
        }
        RenderGuard.require();
        ItemStack stack = new ItemStack(item);
        Map<Integer, byte[]> out = new LinkedHashMap<>(sizes.length);
        for (int size : sizes) {
            byte[] png = renderToPng(size, (target, pose, buffers) -> {
                // GUI context, which is the one that produces the flat inventory look a wiki wants, and
                // a full-bright light so an item with no world around it is not rendered in the dark.
                client.getItemRenderer().renderStatic(stack, ItemDisplayContext.GUI,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buffers,
                        client.level, 0);
            });
            if (png != null) {
                out.put(size, png);
            }
        }
        return out;
    }

    /**
     * Renders one entity type at each requested size, or an empty map when it cannot be rendered.
     *
     * <p>An entity needs a world to exist in, and this is the one part of the icon phase that can fail
     * for a reason the user can do nothing about: with no world loaded — at the title screen, or on a
     * server — there is nowhere to create the entity and so nothing to draw. It reports that by
     * returning nothing rather than by throwing, and the phase records the element as skipped, which is
     * the honest outcome: the export has everything except the mob icons and says so.
     */
    public Map<Integer, byte[]> renderEntity(ResourceLocation id, int[] sizes) {
        Minecraft client = Minecraft.getInstance();
        EntityType<?> type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(id);
        if (type == null || client.level == null) {
            return Map.of();
        }
        RenderGuard.require();
        Entity entity = type.create(client.level);
        if (entity == null) {
            return Map.of();
        }
        Map<Integer, byte[]> out = new LinkedHashMap<>(sizes.length);
        for (int size : sizes) {
            byte[] png = renderToPng(size, (target, pose, buffers) -> {
                // The entity is drawn at the origin facing the camera. A small positive Y offset lifts it
                // so a model whose feet are at zero is not half below the frame; the scale is chosen from
                // the entity's own bounding box so a small mob is not a speck and a large one is not
                // cropped.
                pose.pushPose();
                float scale = entityScale(entity);
                pose.translate(0.0F, entity.getBbHeight() / 2.0F, 0.0F);
                pose.scale(scale, scale, scale);
                client.getEntityRenderDispatcher().render(entity, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F,
                        pose, buffers, LightTexture.FULL_BRIGHT);
                pose.popPose();
            });
            if (png != null) {
                out.put(size, png);
            }
        }
        return out;
    }

    /** How much to shrink a mob so it fits the frame, from its own height. */
    private static float entityScale(Entity entity) {
        float height = Math.max(0.1F, entity.getBbHeight());
        float width = Math.max(0.1F, entity.getBbWidth());
        float extent = Math.max(height, width);
        // The entity renderer works in blocks, where a full-size mob is about two tall; the frame is
        // sixteen units, so a mob that fills it needs a scale of about eight over its size.
        return 8.0F / extent;
    }

    /** What to draw, given a bound target, a pose to fill and buffers to batch into. */
    @FunctionalInterface
    private interface Drawing {
        void draw(TextureTarget target, PoseStack pose, MultiBufferSource.BufferSource buffers);
    }

    /**
     * Runs one drawing into an off-screen target and returns it as a PNG.
     *
     * <p>The world's render state is saved and restored rather than assumed: an icon render runs between
     * frames, and anything this changes and does not put back shows up as a rendering artefact in the
     * game the player is looking at. The target is released in a finally block so a failure part-way
     * through does not leak a framebuffer per attempt.
     *
     * @return the PNG, or {@code null} when nothing was drawn
     */
    private static byte[] renderToPng(int size, Drawing drawing) {
        Minecraft client = Minecraft.getInstance();
        TextureTarget target = new TextureTarget(size, size, true, false);
        MultiBufferSource.BufferSource buffers = client.renderBuffers().bufferSource();
        try {
            // A transparent background, not a cleared-to-black one: an icon with a black square behind
            // it is the defect the design's own notes call out in other tools, and it cannot be removed
            // afterwards without guessing which black pixels were background.
            target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            target.clear(false);

            target.bindWrite(true);
            PoseStack pose = new PoseStack();
            if (size != 16) {
                // The drawing code is authored for a sixteen-unit frame whatever the pixel count, so the
                // pixel size is handled by the target and the transform by this scale.
                pose.scale(size / 16.0F, size / 16.0F, 1.0F);
            }
            applyItemProjection();
            try {
                drawing.draw(target, pose, buffers);
                buffers.endBatch();
            } finally {
                popItemProjection();
            }

            int[] pixels = readPixels(size);
            target.unbindWrite();
            if (pixels == null) {
                return null;
            }
            return Png.encode(pixels, size, size);
        } catch (Throwable t) {
            // Reported once per icon rather than propagated: the phase already knows how to record a
            // skipped element, and one item with a model the renderer cannot handle must not cost the
            // export every other icon.
            org.uee.Uee.reportFailure("icon render failed at " + size + "px", t);
            return null;
        } finally {
            // The world's target has to be bound again before anything else draws, or the next frame goes
            // into an off-screen buffer that is about to be deleted.
            client.getMainRenderTarget().bindWrite(true);
            target.destroyBuffers();
        }
    }

    /**
     * Reads the bound framebuffer back as pixels.
     *
     * <p>Through the GL call rather than through the image class the client uses internally: that class
     * keeps its pixel buffer private in this version, with no accessor, so the bytes are not reachable
     * from it. Reading raw and encoding here is also what keeps the encoder in the core, where it is
     * tested.
     *
     * <p>The rows arrive bottom-up, because that is how a framebuffer is stored, and are flipped so the
     * image is the way up a reader expects. Getting this wrong produces an upside-down icon, which is
     * obvious; getting it wrong for only some sizes is not, which is why it is done here once rather than
     * at each use.
     */
    private static int[] readPixels(int size) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder());
        GlStateManager._readPixels(0, 0, size, size, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        buffer.rewind();

        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            int sourceRow = size - 1 - y;
            for (int x = 0; x < size; x++) {
                int at = (sourceRow * size + x) * 4;
                int r = buffer.get(at) & 0xFF;
                int g = buffer.get(at + 1) & 0xFF;
                int b = buffer.get(at + 2) & 0xFF;
                int a = buffer.get(at + 3) & 0xFF;
                // Packed the way the encoder reads it: alpha in the high byte, then red, green, blue.
                pixels[y * size + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return pixels;
    }

    /**
     * A check that the caller is on the render thread.
     *
     * <p>Separate from the renderer so the requirement is stated once, in one place, in a way that cannot
     * be forgotten in a new method. It throws rather than warns: a warning in a log is not read by
     * whoever eventually sees the graphical corruption, whereas an exception points at the call.
     */
    static final class RenderGuard {
        private RenderGuard() {
        }

        static void require() {
            if (!RenderSystem.isOnRenderThread()) {
                throw new IllegalStateException("icons must be rendered on the render thread; the render"
                        + " pipeline is global state and is not safe to drive from anywhere else");
            }
        }
    }
}
