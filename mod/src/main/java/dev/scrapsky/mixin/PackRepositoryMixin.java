package dev.scrapsky.mixin;

import dev.scrapsky.client.RuntimePack;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashSet;
import java.util.Set;

/** Sheet hooks:h_pack_source - puts the runtime pack into the client's resource packs (always on, on top). */
@Mixin(PackRepository.class)
public class PackRepositoryMixin {
    @Shadow @Final @Mutable private Set<RepositorySource> sources;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void scrapsky$addRuntimePack(RepositorySource[] given, CallbackInfo ci) {
        for (RepositorySource s : given) {
            if (s instanceof ClientPackSource) {
                Set<RepositorySource> all = new LinkedHashSet<>(this.sources);
                all.add(RuntimePack.SOURCE);
                this.sources = all;
                return;
            }
        }
    }
}
