package org.uee.datapack;

/**
 * A flow defined as an MC function rather than as a configuration document.
 *
 * <h2>Why there are two kinds of flow</h2>
 *
 * <p>In a Minecraft datapack, "a sequence of steps" is already a thing: it is an {@code .mcfunction}.
 * So there are two honest ways for a pack to define a flow, and they are good at different things:
 *
 * <ul>
 *   <li><b>A configuration flow</b> ({@link FlowDefinition}) — one command that runs a standard
 *       export with different settings. Best when the flow is "the same run, described differently".
 *   <li><b>A function flow</b> (this type) — a sequence of commands, of which UEE's are some. Best
 *       when the flow is "do these things in this order", because it can interleave UEE calls with the
 *       pack's own commands, branch on {@code execute if}, and take arguments through
 *       {@code function … with storage}.
 * </ul>
 *
 * <p>UEE does not need to run a function flow itself — the game already knows how to. What it
 * contributes is discovery: a flow defined either way appears in {@code /uee flows} and is reachable
 * under one name, so a user does not have to know which mechanism a pack chose.
 *
 * @param id the flow's id, as discovered
 * @param namespace the datapack's namespace
 * @param resourceId the function's resource path, e.g. {@code mypack:uee/export}
 * @param description optional note, when the pack supplied one
 */
public record FunctionFlow(String id, String namespace, String resourceId, String description) {

    /** Directory under a datapack's function namespace where UEE looks for flow functions. */
    public static final String FUNCTION_DIR = "uee";

    public FunctionFlow {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("function flow id is required");
        }
        if (resourceId == null || resourceId.isEmpty()) {
            throw new IllegalArgumentException("function flow '" + id + "' has no resource id");
        }
        description = description == null || description.isEmpty() ? null : description;
    }

    /** The flow's fully qualified id, as it is written on the command line. */
    public String qualifiedId() {
        return namespace == null ? id : namespace + ":" + id;
    }

    /**
     * Builds the expected resource path for a flow name in a namespace.
     *
     * <p>The convention lives here rather than in each loader so all four agree on where a flow
     * function is found: {@code data/<namespace>/function/uee/<name>.mcfunction}.
     */
    public static String resourceId(String namespace, String name) {
        return namespace + ":" + FUNCTION_DIR + "/" + name;
    }

    /** Extracts the flow name from a function resource path, or {@code null} when it is not a flow. */
    public static String flowNameOf(String functionPath) {
        if (functionPath == null) {
            return null;
        }
        int colon = functionPath.indexOf(':');
        String path = colon < 0 ? functionPath : functionPath.substring(colon + 1);
        String prefix = FUNCTION_DIR + "/";
        if (!path.startsWith(prefix) || path.length() == prefix.length()) {
            return null;
        }
        String name = path.substring(prefix.length());
        // A pack may group its flows deeper; the remainder is used as the name so nested functions
        // still have distinct ids rather than colliding on their leaf name.
        return name.isEmpty() ? null : name;
    }

    /** The namespace part of a function resource path, or {@code null}. */
    public static String namespaceOf(String functionPath) {
        if (functionPath == null) {
            return null;
        }
        int colon = functionPath.indexOf(':');
        return colon <= 0 ? null : functionPath.substring(0, colon);
    }
}
