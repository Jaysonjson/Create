package com.simibubi.create.content.contraptions.components.structureMovement.train;

import com.simibubi.create.content.contraptions.components.structureMovement.train.capability.MinecartController;
import com.simibubi.create.foundation.utility.Couple;
import com.simibubi.create.foundation.utility.Iterate;
import com.simibubi.create.foundation.utility.VecHelper;

import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.item.minecart.AbstractMinecartEntity;
import net.minecraft.state.properties.RailShape;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

public class CouplingPhysics {

	public static void tick(World world) {
		CouplingHandler.forEachLoadedCoupling(world, c -> tickCoupling(world, c));
	}

	public static void tickCoupling(World world, Couple<MinecartController> c) {
		Couple<AbstractMinecartEntity> carts = c.map(MinecartController::cart);
		float couplingLength = c.getFirst().getCouplingLength(true);
		softCollisionStep(world, carts, couplingLength);
		hardCollisionStep(world, carts, couplingLength);
	}

	public static void hardCollisionStep(World world, Couple<AbstractMinecartEntity> carts, double couplingLength) {
		Couple<Vector3d> corrections = Couple.create(null, null);
		Couple<Float> maxSpeed = carts.map(AbstractMinecartEntity::getMaxCartSpeedOnRail);
		boolean firstLoop = true;
		for (boolean current : new boolean[] { true, false, true }) {
			AbstractMinecartEntity cart = carts.get(current);
			AbstractMinecartEntity otherCart = carts.get(!current);

			float stress = (float) (couplingLength - cart.getPositionVec()
				.distanceTo(otherCart.getPositionVec()));

			RailShape shape = null;
			BlockPos railPosition = cart.getCurrentRailPosition();
			BlockState railState = world.getBlockState(railPosition.up());

			if (railState.getBlock() instanceof AbstractRailBlock) {
				AbstractRailBlock block = (AbstractRailBlock) railState.getBlock();
				shape = block.getRailDirection(railState, world, railPosition, cart);
			}

			Vector3d correction = Vector3d.ZERO;
			Vector3d pos = cart.getPositionVec();
			Vector3d link = otherCart.getPositionVec()
				.subtract(pos);
			float correctionMagnitude = firstLoop ? -stress / 2f : -stress;
			correction = shape != null ? followLinkOnRail(link, pos, correctionMagnitude, shape).subtract(pos)
				: link.normalize()
					.scale(correctionMagnitude);

			float maxResolveSpeed = 1.75f;
			correction = VecHelper.clamp(correction, Math.min(maxResolveSpeed, maxSpeed.get(current)));

			if (corrections.get(current) == null)
				corrections.set(current, correction);

			if (shape != null)
				MinecartSim2020.moveCartAlongTrack(cart, correction, railPosition, railState);
			else {
				cart.move(MoverType.SELF, correction);
				cart.setMotion(cart.getMotion()
					.scale(0.5f));
			}
			firstLoop = false;
		}
	}

	public static void softCollisionStep(World world, Couple<AbstractMinecartEntity> carts, double couplingLength) {

		Couple<Vector3d> positions = carts.map(Entity::getPositionVec);
		Couple<Float> maxSpeed = carts.map(AbstractMinecartEntity::getMaxCartSpeedOnRail);
		Couple<Boolean> canAddmotion = carts.map(MinecartSim2020::canAddMotion);

		Couple<RailShape> shapes = carts.map(current -> {
			BlockPos railPosition = current.getCurrentRailPosition();
			BlockState railState = world.getBlockState(railPosition.up());
			if (!(railState.getBlock() instanceof AbstractRailBlock))
				return null;
			AbstractRailBlock block = (AbstractRailBlock) railState.getBlock();
			return block.getRailDirection(railState, world, railPosition, current);
		});

		Couple<Vector3d> motions = carts.map(MinecartSim2020::predictMotionOf);
		Couple<Vector3d> nextPositions = positions.copy();
		nextPositions.replaceWithParams(Vector3d::add, motions);

		float futureStress = (float) (couplingLength - nextPositions.getFirst()
			.distanceTo(nextPositions.getSecond()));
		if (Math.abs(futureStress) < 1 / 128f)
			return;

		for (boolean current : Iterate.trueAndFalse) {
			Vector3d correction = Vector3d.ZERO;
			Vector3d pos = nextPositions.get(current);
			Vector3d link = nextPositions.get(!current)
				.subtract(pos);
			float correctionMagnitude = -futureStress / 2f;

			if (canAddmotion.get(current) != canAddmotion.get(!current))
				correctionMagnitude = !canAddmotion.get(current) ? 0 : correctionMagnitude * 2;

			RailShape shape = shapes.get(current);
			correction = shape != null ? followLinkOnRail(link, pos, correctionMagnitude, shape).subtract(pos)
				: link.normalize()
					.scale(correctionMagnitude);
			correction = VecHelper.clamp(correction, maxSpeed.get(current));
			motions.set(current, motions.get(current)
				.add(correction));
		}

		motions.replaceWithParams(VecHelper::clamp, maxSpeed);
		carts.forEachWithParams(Entity::setMotion, motions);
	}

	public static Vector3d followLinkOnRail(Vector3d link, Vector3d cart, float diffToReduce, RailShape shape) {
		Vector3d railAxis = MinecartSim2020.getRailVec(shape);
		double dotProduct = railAxis.dotProduct(link);
		if (Double.isNaN(dotProduct) || dotProduct == 0 || diffToReduce == 0)
			return cart;

		Vector3d axis = railAxis.scale(-Math.signum(dotProduct));
		Vector3d center = cart.add(link);
		double radius = link.length() - diffToReduce;
		Vector3d intersectSphere = VecHelper.intersectSphere(cart, axis, center, radius);

		// Cannot satisfy on current rail vector
		if (intersectSphere == null)
			return cart.add(VecHelper.project(link, axis));

		return intersectSphere;
	}

}
