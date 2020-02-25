/*
 * Copyright (c) 2020, Wild Adventure
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 * 4. Redistribution of this software in source or binary forms shall be free
 *    of all charges or fees to the recipient of this software.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.gmail.filoghost.regencustomworlds;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.craftbukkit.v1_12_R1.CraftChunk;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import net.minecraft.server.v1_12_R1.BiomeBase;
import net.minecraft.server.v1_12_R1.NBTCompressedStreamTools;
import net.minecraft.server.v1_12_R1.NBTTagCompound;
import net.minecraft.server.v1_12_R1.NBTTagList;
import net.minecraft.server.v1_12_R1.NibbleArray;
import net.minecraft.server.v1_12_R1.RegionFile;
import net.minecraft.server.v1_12_R1.TileEntity;

@SuppressWarnings("deprecation")
public class CloneChunkGenerator extends ChunkGenerator {

	private File regionsFolder;
	private Cache<CoordXZ, RegionFile> regionFilesByRegionCache = CacheBuilder.newBuilder().maximumSize(4).concurrencyLevel(1).build(); // Should be LRU cache
	private Cache<CoordXZ, NBTTagList> tileEntitiesByChunkCache = CacheBuilder.newBuilder().maximumSize(16).concurrencyLevel(1).build();
	
	
	public CloneChunkGenerator(File sourceWorldFolder) {
		if (!sourceWorldFolder.isDirectory()) {
			throw new IllegalArgumentException("Source world folder '" + sourceWorldFolder.getName() + "' doesn't exist");
		}
		
		this.regionsFolder = new File(sourceWorldFolder, "region");
		if (!sourceWorldFolder.isDirectory()) {
			throw new IllegalArgumentException("Folder 'region' for world '" + sourceWorldFolder.getName() + "' doesn't exist");
		}
	}
	
	
	private RegionFile getRegionFile(int regionX, int regionZ) {
		CoordXZ regionXZ = new CoordXZ(regionX, regionZ);
		
		try {
			return regionFilesByRegionCache.get(regionXZ, () -> {
				File file = new File(regionsFolder, "r." + regionX + "." + regionZ + ".mca"); 
				
				if (!file.isFile()) {
					return null;
				}
				
				return new RegionFile(file);
			});
			
		} catch (ExecutionException e) {
			// Should not happen
			RegenCustomWorlds.logError("Couldn't get region file " + regionX + ", " + regionZ + " from cache", e);
			return null;
		}
	}
	
	
	private NBTTagCompound getChunkLevelNBT(CoordXZ chunkXZ) {
		int regionX = chunkXZ.getX() >> 5; // ">> 5" is similar to "/ 32" (but isn't specular around 0)
		int regionZ = chunkXZ.getZ() >> 5;
		
		RegionFile regionFile = getRegionFile(regionX, regionZ);
		if (regionFile == null) {
			// Region doesn't exist
			return null;
		}
		
		DataInputStream inputStream = regionFile.a(chunkXZ.getX() & 31, chunkXZ.getZ() & 31); // "& 31" is similar to "% 32" (but doesn't output negative numbers)
		if (inputStream == null) {
			// Chunk doesn't exist
			return null;
		}
		
		NBTTagCompound regionNBT;
		
		try {
			regionNBT = NBTCompressedStreamTools.a(inputStream);
		} catch (IOException e) {
			RegenCustomWorlds.logError("Region r." + regionX + "." + regionZ + ".mca has invalid NBT", e);
			return null;
		}
		
		if (!regionNBT.hasKeyOfType("Level", NBTTypeID.COMPOUND)) {
			RegenCustomWorlds.logError("Region r." + regionX + "." + regionZ + ".mca has no 'Level' NBT");
			return null;
		}
		
		return regionNBT.getCompound("Level");
	}

	
	@Override
	public ChunkData generateChunkData(World world, Random random, int chunkX, int chunkZ, BiomeGrid biomeGrid) {
		ChunkData chunkData = createChunkData(world);
		CoordXZ chunkXZ = new CoordXZ(chunkX, chunkZ);
		
		NBTTagCompound levelNBT = getChunkLevelNBT(chunkXZ);
		if (levelNBT == null) {
			fillBiomeGrid(biomeGrid, Biome.PLAINS); // Generate AIR for chunks not existing in the custom world
			return chunkData;
		}
		
		if (!levelNBT.hasKeyOfType("Sections", NBTTypeID.LIST)) {
			RegenCustomWorlds.logError("Chunk " + chunkX + ", " + chunkZ + " 'Level' NBT has no 'Sections' NBT");
			fillBiomeGrid(biomeGrid, Biome.PLAINS);
			return chunkData;
		}
		
		NBTTagList sectionsListNBT = levelNBT.getList("Sections", NBTTypeID.COMPOUND);
		
		for (int sectionIndex = 0; sectionIndex < sectionsListNBT.size(); sectionIndex++) {
			NBTTagCompound sectionNBT = sectionsListNBT.get(sectionIndex);
			int sectionBaseY = sectionNBT.getByte("Y") << 4;
			byte[] blocksIDs = sectionNBT.getByteArray("Blocks");
			NibbleArray blocksIDsExtra = sectionNBT.hasKeyOfType("Add", NBTTypeID.BYTE_ARRAY) ? new NibbleArray(sectionNBT.getByteArray("Add")) : null;
			NibbleArray blocksData = new NibbleArray(sectionNBT.getByteArray("Data"));
			
			for (int combinedPosIndex = 0; combinedPosIndex < 4096; combinedPosIndex++) {
				int blockIDExtra = blocksIDsExtra == null ? 0 : blocksIDsExtra.a(combinedPosIndex); // a() = getFromIndex()
				int blockID = blockIDExtra << 8 | (blocksIDs[combinedPosIndex] & 255);
				byte blockData = (byte) (blocksData.a(combinedPosIndex) & 15);
			
				int x = combinedPosIndex & 15;
				int y = (combinedPosIndex >> 8) & 15;
				int z = (combinedPosIndex >> 4) & 15;
				
				chunkData.setBlock(x, sectionBaseY + y, z, blockID, blockData);
			}

			
			// Slower but more readable method
//			DataPaletteBlock parsedBlocks = new DataPaletteBlock();
//			parsedBlocks.a(blocksIDs, blocksData, blocksIDsExtra);
//			for (int x = 0; x < 16; x++) {
//				for (int y = 0; y < 16; y++) {
//					for (int z = 0; z < 16; z++) {
//						IBlockData blockData = parsedBlocks.a(x, y, z);
//						if (Block.getId(blockData.getBlock()) != 0) {
//							System.out.println("Block Data at " + x + ", " + y + ", " + z + " = " + Block.getId(blockData.getBlock()) + ": " + blockData.getBlock().toLegacyData(blockData));
//						}
//
//					}
//				}
//			}
		}
		
		// They will probably reused later by the populator if the chunk is not at the edge of the world
		NBTTagList tileEntitiesNBT;
		if (levelNBT.hasKeyOfType("TileEntities", NBTTypeID.LIST)) {
			tileEntitiesNBT = levelNBT.getList("TileEntities", NBTTypeID.COMPOUND);
		} else {
			tileEntitiesNBT = null;
		}
		tileEntitiesByChunkCache.put(chunkXZ, tileEntitiesNBT);
		
		
		if (!levelNBT.hasKeyOfType("Biomes", NBTTypeID.BYTE_ARRAY)) {
			RegenCustomWorlds.logError("Chunk " + chunkX + ", " + chunkZ + " 'Level' NBT has no 'Biomes' NBT");
			fillBiomeGrid(biomeGrid, Biome.PLAINS);
			return chunkData;
		}
		
		byte[] biomeIDs = levelNBT.getByteArray("Biomes");
		if (biomeIDs.length != 256) {
			RegenCustomWorlds.logError("Chunk " + chunkX + ", " + chunkZ + " 'Level' NBT has 'Biomes' NBT with wrong length " + biomeIDs.length);
			return chunkData;
		}
		
		BiomeBase[] biomeBases = null;
		try {
			Field biomeBasesField = biomeGrid.getClass().getDeclaredField("biome");
			biomeBasesField.setAccessible(true);
			biomeBases = (BiomeBase[]) biomeBasesField.get(biomeGrid);
		} catch (Throwable t) {
			RegenCustomWorlds.logError("Couldn't access 'biome' field in class " + biomeGrid.getClass().getName(), t);
		}
		
		for (int i = 0; i < biomeIDs.length; i++) {
//			int z = i >> 4;
//			int x = i & 15;
//			biomeGrid.setBiome(x, z);
			
			biomeBases[i] = BiomeBase.getBiome(biomeIDs[i]);
			if (biomeBases[i] == null) {
				RegenCustomWorlds.logError("Couldn't find biome with ID " + biomeIDs[i]);
			}
		}
		
		return chunkData;
	}

	/*
	 * Populators are called only when adjacent chunks have been generated
	 */
	private List<BlockPopulator> populators = Collections.singletonList(new BlockPopulator() {
		
		@Override
		public void populate(World world, Random random, Chunk chunk) {
			CoordXZ chunkXZ = new CoordXZ(chunk.getX(), chunk.getZ());
			
			NBTTagList tileEntitiesNBT;
			
			try {
				tileEntitiesNBT = tileEntitiesByChunkCache.get(chunkXZ, () -> {
					NBTTagCompound levelNBT = getChunkLevelNBT(chunkXZ);
					if (levelNBT == null) {
						RegenCustomWorlds.logError("Couldn't get 'Level' NBT for decorating chunk " + chunk.getX() + ", " + chunk.getZ());
						return null;
					}
					
					if (levelNBT.hasKeyOfType("TileEntities", NBTTypeID.LIST)) {
						return levelNBT.getList("TileEntities", NBTTypeID.COMPOUND);
					} else {
						return null;
					}
				});
			} catch (ExecutionException e) {
				// Should not happen
				RegenCustomWorlds.logError("Couldn't get tile entities for decorating chunk " + chunk.getX() + ", " + chunk.getZ() + " from cache", e);
				return;
			}

			if (tileEntitiesNBT == null) {
				return;
			}
			
			net.minecraft.server.v1_12_R1.Chunk nmsChunk = ((CraftChunk) chunk).getHandle();
			
			for (int i = 0; i < tileEntitiesNBT.size(); i++) {
				NBTTagCompound tileEntityNBT = tileEntitiesNBT.get(i);
				TileEntity tileEntity = TileEntity.create(nmsChunk.getWorld(), tileEntityNBT);

				if (tileEntity != null) {
					nmsChunk.a(tileEntity);
				}
			}
		}
	});
	
	@Override
	public List<BlockPopulator> getDefaultPopulators(World world) {
		return populators;
	}	
	
	
	private void fillBiomeGrid(BiomeGrid biomeGrid, Biome biome) {
		for (int x = 0; x < 16; x++) {
			for (int z = 0; z < 16; z++) {
				biomeGrid.setBiome(x, z, biome);
			}
		}
	}

}
