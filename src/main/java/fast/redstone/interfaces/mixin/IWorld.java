package fast.redstone.interfaces.mixin;

import fast.redstone.v1.WireV1;

import net.minecraft.util.math.BlockPos;

public interface IWorld {
	
	public void reset();
	
	public int getCount();
	
	public WireV1 getWireV1(BlockPos pos);
	
	public void setWireV1(BlockPos pos, WireV1 wire, boolean updateConnections);
	
}
