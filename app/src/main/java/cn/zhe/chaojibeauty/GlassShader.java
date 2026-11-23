package cn.zhe.chaojibeauty;

import android.graphics.RuntimeShader;
import java.util.Collections;
import java.util.List;

/** Compiles a material with the host process's smooth-corner distance function. */
final class GlassShader {
    static final String INPUT = "uniBDFBitmap";

    static RuntimeShader compile(ClassLoader loader, String generatorMethod, String optics)
            throws ReflectiveOperationException {
        Class<?> corner = loader.loadClass("com.oplus.posteffect.params.CornerType");
        Object g2 = corner.getMethod("valueOf", String.class).invoke(null, "G2");
        Class<?> generator = loader.loadClass("com.oplus.posteffect.agsl.BlurDrawableShaderBaseStringKt");
        StringBuilder source = new StringBuilder();
        generator.getDeclaredMethod(generatorMethod, StringBuilder.class, int.class, int.class,
                corner, boolean.class, List.class).invoke(null, source, 0, 0, g2, false, Collections.emptyList());
        int entry = source.indexOf("half4 main(float2 position)");
        if (entry < 0 || source.indexOf("smoothSDF_D(") < 0 || source.indexOf("uniform shader " + INPUT) < 0)
            throw new IllegalStateException("Unsupported ColorOS material program");
        source.setLength(entry);
        source.append(optics);
        Class<?> factory = loader.loadClass("com.oplus.graphics.OplusRuntimeShader");
        return (RuntimeShader)factory.getMethod("createRuntimeShader", String.class, boolean.class)
                .invoke(null, source.toString(), true);
    }
}
