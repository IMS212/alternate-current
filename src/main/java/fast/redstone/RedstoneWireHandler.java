package fast.redstone;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import fast.redstone.interfaces.mixin.IWireBlock;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public class RedstoneWireHandler {
	
	private static final Direction[] DIRECTIONS = new Direction[] {
			Direction.DOWN,
			Direction.UP,
			Direction.NORTH,
			Direction.SOUTH,
			Direction.WEST,
			Direction.EAST
	};
	private static final int DOWN = 0;
	private static final int UP = 1;
	/*private static final int NORTH = 2;
	private static final int SOUTH = 3;
	private static final int WEST = 4;
	private static final int EAST = 5;*/
	
	public final int MAX_POWER = 15;
	
	private final Block wireBlock;
	private final List<Node> nodes;
	private final List<Wire> wires;
	private final Map<BlockPos, Node> posToNode;
	private final Queue<Wire> poweredWires;
	private final List<BlockPos> updatedWires;
	private final Set<BlockPos> blockUpdates;
	
	private int nodeCount;
	private int wireCount;
	
	private World world;
	private boolean updatingPower;
	
	public RedstoneWireHandler(Block wireBlock) {
		if (!(wireBlock instanceof IWireBlock)) {
			throw new IllegalArgumentException(String.format("The given Block must implement %s", IWireBlock.class));
		}
		
		this.wireBlock = wireBlock;
		this.nodes = new ArrayList<>();
		this.wires = new ArrayList<>();
		this.posToNode = new HashMap<>();
		this.poweredWires = new PriorityQueue<>();
		this.updatedWires = new ArrayList<>();
		this.blockUpdates = new LinkedHashSet<>();
	}
	
	public void updatePower(World world, BlockPos pos, BlockState state) {
		if (updatingPower) {
			return;
		}
		
		this.world = world;
		
		nodeCount = 0;
		wireCount = 0;
		
		Node node = addNode(pos, state);
		
		if (node.isWire()) {
			Wire wire = (Wire)node;
			findNeighborsAndConnections(wire, true);
			
			if (wire.power == getReceivedPower(wire, false)) {
				posToNode.clear();
				return;
			} else {
				wire.inNetwork = true;
				updateNetwork();
			}
		} else {
			posToNode.clear();
			return; // We should never get here
		}
		
		findPoweredWires();
		
		posToNode.clear();
		
		letPowerFlow();
		
		System.out.println("collecting neighbor positions");
		long start = System.nanoTime();
		
		blockUpdates.removeAll(updatedWires);
		List<BlockPos> positions = new ArrayList<>(blockUpdates);
		
		updatedWires.clear();
		blockUpdates.clear();
		
		System.out.println("t: " + (System.nanoTime() - start));
		System.out.println("updating neighbors");
		start = System.nanoTime();
		
		for (int index = positions.size() - 1; index >= 0; index--) {
			world.updateNeighbor(positions.get(index), wireBlock, pos);
		}
		
		System.out.println("t: " + (System.nanoTime() - start));
	}
	
	private void updateNetwork() {
		System.out.println("updating network");
		long start = System.nanoTime();
		
		for (int index = 1; index < wireCount; index++) {
			Wire wire = wires.get(index);
			findNeighborsAndConnections(wire, wire.inNetwork);
		}
		
		System.out.println("t: " + (System.nanoTime() - start) + " - network size: " + wireCount);
	}
	
	private void findNeighborsAndConnections(Wire wire, boolean findConnections) {
		BlockPos pos = wire.pos;
		
		for (int index = 0; index < DIRECTIONS.length; index++) {
			Direction dir = DIRECTIONS[index];
			BlockPos side = pos.offset(dir);
			
			Node node = getOrAddNode(side);
			wire.neighbors[index] = node;
			
			if (findConnections && dir.getAxis().isHorizontal()) {
				if (node.isWire()) {
					addConnection(wire, (Wire)node, true, true);
					continue;
				}
				
				boolean sideIsSolid = (node.type == NodeType.SOLID_BLOCK);
				
				if (wire.aboveNode().type != NodeType.SOLID_BLOCK) {
					BlockPos aboveSide = side.up();
					Node aboveSideNode = getOrAddNode(aboveSide);
					
					if (aboveSideNode.isWire()) {
						addConnection(wire, (Wire)aboveSideNode, true, sideIsSolid);
					}
				}
				if (!sideIsSolid) {
					BlockPos belowSide = side.down();
					Node belowSideNode = getOrAddNode(belowSide);
					
					if (belowSideNode.isWire()) {
						Node belowNode = wire.belowNode();
						boolean belowIsSolid = (belowNode.type == NodeType.SOLID_BLOCK);
						
						addConnection(wire, (Wire)belowSideNode, belowIsSolid, true);
					}
				}
			}
		}
	}
	
	private void addConnection(Wire wire, Wire connectedWire, boolean out, boolean in) {
		wire.addConnection(connectedWire, out, in);
		if (out) {
			connectedWire.inNetwork = true;
		}
	}
	
	private Node getOrAddNode(BlockPos pos) {
		Node node = getNode(pos);
		return node == null ? addNode(pos) : node;
	}
	
	private Node getNode(BlockPos pos) {
		return posToNode.get(pos);
	}
	
	private Node addNode(BlockPos pos) {
		return addNode(pos, world.getBlockState(pos));
	}
	
	private Node addNode(BlockPos pos, BlockState state) {
		Node node = createNode(pos, state);
		posToNode.put(pos, node);
		
		return node;
	}
	
	private Node createNode(BlockPos pos, BlockState state) {
		if (state.isOf(wireBlock)) {
			Wire wire = nextWire();
			wire.set(pos, state);
			
			return wire;
		}
		
		Node node = nextNode();
		
		if (state.emitsRedstonePower()) {
			node.set(NodeType.REDSTONE_COMPONENT, pos, state);
		} else if (state.isSolidBlock(world, pos)) {
			node.set(NodeType.SOLID_BLOCK, pos, state);
		} else {
			node.set(NodeType.OTHER, pos, state);
		}
		
		return node;
	}
	
	private Node nextNode() {
		while (nodeCount >= nodes.size()) {
			nodes.add(new Node());
		}
		
		return nodes.get(nodeCount++);
	}
	
	private Wire nextWire() {
		while (wireCount >= wires.size()) {
			wires.add(new Wire());
		}
		
		return wires.get(wireCount++);
	}
	
	private void findPoweredWires() {
		System.out.println("finding powered wires");
		long start = System.nanoTime();
		
		for (int index = 0; index < wires.size(); index++) {
			Wire wire = wires.get(index);
			
			if (!wire.inNetwork) {
				continue;
			}
			
			wire.power = getReceivedPower(wire);
			System.out.println("received power: " + wire.power);
			if (wire.power > 0) {
				poweredWires.add(wire);
			}
		}
		
		poweredWires.add(wires.get(0));
		
		System.out.println("t: " + (System.nanoTime() - start) + " - powered wires count: " + poweredWires.size());
	}
	
	private int getReceivedPower(Wire wire) {
		return getReceivedPower(wire, true);
	}
	
	private int getReceivedPower(Wire wire, boolean ignoreNetworkConnections) {
		int powerFromNeighbors = getPowerFromNeighbors(wire);
		
		if (powerFromNeighbors >= MAX_POWER) {
			return MAX_POWER;
		}
		
		int powerFromConnections = getPowerFromConnections(wire, ignoreNetworkConnections);
		
		if (powerFromConnections > powerFromNeighbors) {
			return powerFromConnections;
		}
		
		return powerFromNeighbors;
	}
	
	private int getPowerFromNeighbors(Wire wire) {
		int power = 0;
		
		for (int index = 0; index < DIRECTIONS.length; index++) {
			Node node = wire.neighbors[index];
			
			if (node.type == NodeType.WIRE || node.type == NodeType.OTHER) {
				continue;
			}
			
			int powerFromNeighbor;
			
			if (node.type == NodeType.REDSTONE_COMPONENT) {
				powerFromNeighbor = node.state.getWeakRedstonePower(world, node.pos, DIRECTIONS[index]);
			} else if (node.type == NodeType.SOLID_BLOCK) {
				powerFromNeighbor = getStrongPowerTo(node.pos, DIRECTIONS[index].getOpposite());
			} else {
				continue; // We should never get here
			}
			
			if (powerFromNeighbor > power) {
				power = powerFromNeighbor;
				
				if (power > MAX_POWER) {
					return MAX_POWER;
				}
			}
		}
		
		return power;
	}
	
	private int getStrongPowerTo(BlockPos pos, Direction ignore) {
		int power = 0;
		
		for (Direction dir : DIRECTIONS) {
			if (dir == ignore) {
				continue;
			}
			
			BlockPos side = pos.offset(dir);
			Node node = getOrAddNode(side);
			
			if (node.type == NodeType.REDSTONE_COMPONENT) {
				int strongPower = node.state.getStrongRedstonePower(world, side, dir);
				
				if (strongPower > power) {
					power = strongPower;
					
					if (power >= MAX_POWER) {
						return MAX_POWER;
					}
				}
			}
		}
		
		return power;
	}
	
	private int getPowerFromConnections(Wire wire, boolean ignoreNetworkConnections) {
		int power = 0;
		
		for (Wire connectedWire : wire.connectionsIn) {
			if (!ignoreNetworkConnections || !connectedWire.inNetwork) {
				int powerFromWire = connectedWire.power - 1;
				
				if (powerFromWire > power) {
					power = powerFromWire;
				}
			}
		}
		
		return power;
	}
	
	private void letPowerFlow() {
		System.out.println("updating power");
		long start = System.nanoTime();
		
		updatingPower = true;
		
		while (!poweredWires.isEmpty()) {
			Wire wire = poweredWires.poll();
			
			if (!wire.inNetwork) {
				continue;
			}
			
			int nextPower = wire.power - 1;
			
			wire.inNetwork = false;
			wire.isPowerSource = false;
			
			updateWireState(wire);
			
			for (Wire connectedWire : wire.connectionsOut) {
				if (connectedWire.inNetwork && !connectedWire.isPowerSource) {
					connectedWire.power = nextPower;
					
					poweredWires.add(connectedWire);
					connectedWire.isPowerSource = true;
				}
			}
		}
		
		updatingPower = false;
		
		System.out.println("t: " + (System.nanoTime() - start));
	}
	
	private void updateWireState(Wire wire) {
		if (wire.power < 0) {
			wire.power = 0;
		}
		
		BlockState newState = wire.state.with(Properties.POWER, wire.power);
		
		if (newState != wire.state) {
			world.setBlockState(wire.pos, newState, 2);
			queueBlockUpdates(wire);
		}
		
		updatedWires.add(wire.pos);
	}
	
	private void queueBlockUpdates(Wire wire) {
		collectNeighborPositions(wire.pos, blockUpdates);
	}
	
	public void collectNeighborPositions(BlockPos pos, Collection<BlockPos> positions) {
		BlockPos down = pos.down();
		BlockPos up = pos.up();
		BlockPos north = pos.north();
		BlockPos south = pos.south();
		BlockPos west = pos.west();
		BlockPos east = pos.east();
		
		positions.add(down);
		positions.add(up);
		positions.add(north);
		positions.add(south);
		positions.add(west);
		positions.add(east);
		
		positions.add(down.north());
		positions.add(up.south());
		positions.add(down.south());
		positions.add(up.north());
		positions.add(down.west());
		positions.add(up.east());
		positions.add(down.east());
		positions.add(up.west());
		
		positions.add(north.west());
		positions.add(south.east());
		positions.add(west.south());
		positions.add(east.north());
		
		positions.add(down.down());
		positions.add(up.up());
		positions.add(north.north());
		positions.add(south.south());
		positions.add(west.west());
		positions.add(east.east());
	}
	
	private enum NodeType {
		WIRE, REDSTONE_COMPONENT, SOLID_BLOCK, OTHER;
	}
	
	private class Node {
		
		public NodeType type;
		public BlockPos pos;
		public BlockState state;
		
		public Node() {
			
		}
		
		public void set(NodeType type, BlockPos pos, BlockState state) {
			this.type = type;
			this.pos = pos;
			this.state = state;
		}
		
		public boolean isWire() {
			return type == NodeType.WIRE;
		}
	}
	
	private class Wire extends Node implements Comparable<Wire> {
		
		public final Node[] neighbors;
		public final List<Wire> connectionsOut;
		public final List<Wire> connectionsIn;
		
		public int power;
		public boolean inNetwork;
		public boolean isPowerSource;
		
		public Wire() {
			this.neighbors = new Node[DIRECTIONS.length];
			this.connectionsOut = new ArrayList<>(4);
			this.connectionsIn = new ArrayList<>(4);
		}
		
		public void set(BlockPos pos, BlockState state) {
			super.set(NodeType.WIRE, pos, state);
			
			if (!state.isOf(wireBlock)) {
				throw new IllegalArgumentException(String.format("The given BlockState must be of the Block of this wire handler: %s", wireBlock.getClass()));
			}
			
			this.connectionsOut.clear();
			this.connectionsIn.clear();
			
			this.power = state.get(Properties.POWER);
			this.inNetwork = false;
			this.isPowerSource = false;
		}
		
		@Override
		public int compareTo(Wire wire) {
			return Integer.compare(wire.power, power);
		}
		
		public Node belowNode() {
			return neighbors[DOWN];
		}
		
		public Node aboveNode() {
			return neighbors[UP];
		}
		
		public void addConnection(Wire wire, boolean out, boolean in) {
			if (out) {
				connectionsOut.add(wire);
			}
			if (in) {
				connectionsIn.add(wire);
			}
		}
	}
}
