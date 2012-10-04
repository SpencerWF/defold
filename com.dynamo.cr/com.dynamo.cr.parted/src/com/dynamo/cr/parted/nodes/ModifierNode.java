package com.dynamo.cr.parted.nodes;

import javax.vecmath.Quat4d;
import javax.vecmath.Vector4d;

import com.dynamo.cr.sceneed.core.Node;
import com.dynamo.particle.proto.Particle.Modifier;

public abstract class ModifierNode extends Node {

    private static final long serialVersionUID = 1L;

    public ModifierNode() {
        super();
        setFlags(Flags.NO_INHERIT_TRANSFORM);
    }

    public ModifierNode(Vector4d translation, Quat4d rotation) {
        super(translation, rotation);
        setFlags(Flags.NO_INHERIT_TRANSFORM);
    }

    public abstract Modifier buildMessage();

}