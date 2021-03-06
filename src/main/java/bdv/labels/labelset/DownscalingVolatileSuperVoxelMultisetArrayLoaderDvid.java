package bdv.labels.labelset;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

import bdv.img.cache.CacheArrayLoader;
import bdv.img.dvid.LabelblkMultisetSetupImageLoader.MultisetSource;
import bdv.labels.labelset.Multiset.Entry;
import bdv.util.dvid.DatasetKeyValue;
import gnu.trove.list.array.TIntArrayList;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.util.IntervalIndexer;


public class DownscalingVolatileSuperVoxelMultisetArrayLoaderDvid implements CacheArrayLoader< VolatileLabelMultisetArray >
{
	private VolatileLabelMultisetArray theEmptyArray;

	private final MultisetSource multisetSource;

	// store the data sets for reading/writing cached SuperVoxelMultisetArray
	// from dvid store for levels > 0
	private final DatasetKeyValue[] dvidStores;

	public DownscalingVolatileSuperVoxelMultisetArrayLoaderDvid( final MultisetSource multisetSource, final DatasetKeyValue[] dvidStores )
	{
		theEmptyArray = new VolatileLabelMultisetArray( 1, false );
		this.multisetSource = multisetSource;
		this.dvidStores = dvidStores;
	}

	@Override
	public VolatileLabelMultisetArray loadArray( final int timepoint, final int setup, final int level, final int[] dimensions, final long[] min ) throws InterruptedException
	{
//		System.out.println( "DownscalingVolatileSuperVoxelMultisetArrayLoader.loadArray(\n"
//				+ "   timepoint = " + timepoint + "\n"
//				+ "   setup = " + setup + "\n"
//				+ "   level = " + level + "\n"
//				+ "   dimensions = " + Util.printCoordinates( dimensions ) + "\n"
//				+ "   min = " + Util.printCoordinates( min ) + "\n"
//				+ ")"
//				);
//		final String filename = getFilename( timepoint, setup, level, min );
		// level 0 does not have an associated data set, thus need to subtract 1
		final RandomAccessibleInterval< LabelMultisetType > source = multisetSource.getSource( timepoint, level );

		final int strideByDimensionSource = 1 << level;
		final int nElementsPerSource = strideByDimensionSource * strideByDimensionSource * strideByDimensionSource;

		if (
				// TODO Adapt if source dimensions are corrected (right now, it's the dimension of the
				// highest resolution source).
				min[ 0 ] * strideByDimensionSource > source.max( 0 ) || min[ 1 ] * strideByDimensionSource > source.max( 1 ) || min[ 2 ] * strideByDimensionSource > source.max( 2 ) ||
				min[ 0 ] < 0 /* source.min( 0 ) */ || min[ 1 ] < 0 /* source.min( 1 ) */ || min[ 2 ] < 0 /* source.min( 2 ) */
			)
		{
			return createOutOfBoundsOnlyZeros( dimensions, nElementsPerSource );
		}

		final DatasetKeyValue store = dvidStores[ level - 1 ]; // -1? TODO
		final String key = getKey( timepoint, setup, min );
		final VolatileLabelMultisetArray cached = tryLoadCached( dimensions, store, key );
		if ( cached != null )
			return cached;

		final int strideByDimensionInput = 1 << ( level - 1 ); // need to get the stride of previous (aka input) level
		final int nElementsPerInputPixel = strideByDimensionInput * strideByDimensionInput * strideByDimensionInput;
		final RandomAccessibleInterval< LabelMultisetType > input = multisetSource.getSource( timepoint, level - 1 );
		final int[] factors = new int[] { 2, 2, 2 }; // for now 2,2,2
		return downscale( input, factors, dimensions, min, store, key, nElementsPerInputPixel );
	}

	@Override
	public int getBytesPerElement()
	{
		return 8;
	}

	private String getKey( final int timepoint, final int setup, final long[] min )
	{
		return String.format( "%d_%d_%d_%d_%d", timepoint, setup, min[0], min[1], min[2] );
	}

	private VolatileLabelMultisetArray tryLoadCached(
			final int[] dimensions,
			final DatasetKeyValue store,
			final String key )
	{
		byte[] bytes;
		try
		{
			// download from data store
			// if key not present, return null
			bytes = store.getKey( key );
		}
		catch ( final Exception e )
		{
			// TODO print stack trace?
//			e.printStackTrace();
			return null;
		}

		final int[] data = new int[ dimensions[ 0 ] * dimensions[ 1 ] * dimensions[ 2 ] ];
		final int listDataSize = bytes.length - 4 * data.length;
		final LongMappedAccessData listData = LongMappedAccessData.factory.createStorage( listDataSize );
		int j = -1;
		for ( int i = 0; i < data.length; ++i )
		{
			data[ i ] = ( 0xff & bytes[ ++j ] ) |
					( ( 0xff & bytes[ ++j ] ) << 8 ) |
					( ( 0xff & bytes[ ++j ] ) << 16 ) |
					( ( 0xff & bytes[ ++j ] ) << 24 );
		}
		for ( int i = 0; i < listDataSize; ++i )
			ByteUtils.putByte( bytes[ ++j ], listData.data, i );
		return new VolatileLabelMultisetArray( data, listData, true );
	}

	private static class SortedPeekIterator implements Comparable< SortedPeekIterator >
	{
		Iterator< Entry< Label > > iter;

		Entry< Label > head;

		void init( final Iterator< Entry< Label > > iter )
		{
			this.iter = iter;
			head = iter.hasNext() ? iter.next() : null;
		}

		void fwd()
		{
			head = iter.hasNext() ? iter.next() : null;
		}

		@Override
		public int compareTo( final SortedPeekIterator o )
		{
			if ( head == null )
				return o.head == null ?
					0 :
					1; // o is smaller because it still has elements
			else
				return o.head == null ?
					-1 : // o is greater because we still have elements
					Long.compare( head.getElement().id(), o.head.getElement().id() );
		}
	}

	private VolatileLabelMultisetArray downscale(
			final RandomAccessibleInterval< LabelMultisetType > input,
			final int[] factors, // (relative to to input)
			final int[] dimensions,
			final long[] min,
			final DatasetKeyValue store,
			final String key,
			final int nElementsPerInputPixel ) throws InterruptedException
	{
		final int n = 3;
		final int[] data = new int[ dimensions[ 0 ] * dimensions[ 1 ] * dimensions[ 2 ] ];
		final LongMappedAccessData listData = LongMappedAccessData.factory.createStorage( 32 );

		int numEntities = 1;
		for ( int i = 0; i < n; ++i )
			numEntities *= dimensions[ i ];

		int numContribs = 1;
		for ( int i = 0; i < n; ++i )
			numContribs *= factors[ i ];

		@SuppressWarnings( "unchecked" )
		final RandomAccess< LabelMultisetType >[] inputs = new RandomAccess[ numContribs ];
		for ( int i = 0; i < numContribs; ++i )
			inputs[ i ] = input.randomAccess();

		final SortedPeekIterator[] iters = new SortedPeekIterator[ numContribs ];
		for ( int i = 0; i < numContribs; ++i )
			iters[ i ] = new SortedPeekIterator();

		final int[] outputPos = new int[ n ];
		final int[] inputOffset = new int[ n ];
		final int[] inputPos = new int[ n ];

		final LabelMultisetEntryList list = new LabelMultisetEntryList( listData, 0 );
		final LabelMultisetEntryList list2 = new LabelMultisetEntryList();
		final TIntArrayList listHashesAndOffsets = new TIntArrayList();
		final LabelMultisetEntry entry = new LabelMultisetEntry( 0, 1 );
		int nextListOffset = 0;
		for ( int o = 0; o < numEntities; ++o )
		{
			IntervalIndexer.indexToPosition( o, dimensions, outputPos );
			for ( int d = 0; d < n; ++d )
				inputOffset[ d ] = ( outputPos[ d ] + ( int ) min[ d ] ) * factors[ d ];

			for ( int i = 0; i < numContribs; ++i )
			{
				IntervalIndexer.indexToPositionWithOffset( i, factors, inputOffset, inputPos );
				// TODO Why does this fail, when inputPos[ d ] > input.dimension( d )?
				// Add super voxel with label zero and count numContribs if out of bounds value
				// is requested. This is a workaround to achieve zero-extension of the input.
				// Need to consider that input min and max are actually min and max of original resolution
				if (
						inputPos[ 0 ] > input.max( 0 ) || inputPos[ 1 ] > input.max( 1 ) || inputPos[ 2 ] > input.max( 2 ) ||
						inputPos[ 0 ] < input.min( 0 ) || inputPos[ 1 ] < input.min( 1 ) || inputPos[ 2 ] < input.min( 2 )
						)
				{
					iters[ i ].init(
							new Iterator< Multiset.Entry<Label> >()
							{

								private boolean hasNext = true;

								@Override
								public boolean hasNext()
								{
									return hasNext;
								}

								@Override
								public Entry< Label > next()
								{
									hasNext = false;
									return new Entry< Label >()
									{

										@Override
										public Label getElement()
										{
											return new Label()
											{

												@Override
												public long id()
												{
													// return background
													return 0;
												}
											};
										}

										@Override
										public int getCount()
										{
											return nElementsPerInputPixel;
										}
									};
								}
							}
					);
				}
				else
				{
					inputs[ i ].setPosition( inputPos );
					iters[ i ].init( inputs[ i ].get().entrySet().iterator() );
				}
			}

			list.createListAt( listData, nextListOffset );
			Arrays.sort( iters );
			if ( iters[ 0 ].head != null )
			{
				long id = iters[ 0 ].head.getElement().id();
				int count = iters[ 0 ].head.getCount();

				iters[ 0 ].fwd();
				Arrays.sort( iters );

				while ( iters[ 0 ].head != null )
				{
					final long headId = iters[ 0 ].head.getElement().id();
					final int headCount = iters[ 0 ].head.getCount();

					if ( headId == id )
					{
						count += headCount;
					}
					else
					{
						entry.setId( id );
						entry.setCount( count );
						list.add( entry );

						id = headId;
						count = headCount;
					}

					iters[ 0 ].fwd();
					Arrays.sort( iters );
				}

				entry.setId( id );
				entry.setCount( count );
				list.add( entry );
			}

			boolean makeNewList = true;
			final int hash = list.hashCode();
			for ( int i = 0; i < listHashesAndOffsets.size(); i += 2 )
			{
				if ( hash == listHashesAndOffsets.get( i ) )
				{
					list2.referToDataAt( listData, listHashesAndOffsets.get( i + 1 ) );
					if ( list.equals( list2 ) )
					{
						makeNewList = false;
						data[ o ] = listHashesAndOffsets.get( i + 1 ) ;
						break;
					}
				}
			}
			if ( makeNewList )
			{
				data[ o ] = nextListOffset;
				listHashesAndOffsets.add( hash );
				listHashesAndOffsets.add( nextListOffset );
				nextListOffset += list.getSizeInBytes();
			}
		}

		final byte[] bytes = new byte[ 4 * data.length + nextListOffset ];
		int j = -1;
		for ( final int d : data )
		{
			bytes[ ++j ] = ( byte ) d;
			bytes[ ++j ] = ( byte ) ( d >> 8 );
			bytes[ ++j ] = ( byte ) ( d >> 16 );
			bytes[ ++j ] = ( byte ) ( d >> 24 );
		}
		for ( int i = 0; i < nextListOffset; ++i )
			bytes[ ++j ] = ByteUtils.getByte( listData.data, i );
		try
		{
			// write VolatileSuperVoxelMultisetArray to dvid store so it
			// can be loaded in future requests
			store.postKey( key, bytes );
		}
		catch ( final IOException e )
		{
			// if writing goes wrong, continue but print the trace
			e.printStackTrace();
		}

		return new VolatileLabelMultisetArray( data, listData, true );
	}

	public VolatileLabelMultisetArray emptyArray( final int[] dimensions )
	{
		int numEntities = 1;
		for ( int i = 0; i < dimensions.length; ++i )
			numEntities *= dimensions[ i ];
		if ( theEmptyArray.getCurrentStorageArray().length < numEntities )
			theEmptyArray = new VolatileLabelMultisetArray( numEntities, false );
		return theEmptyArray;
	}

	private VolatileLabelMultisetArray createOutOfBoundsOnlyZeros( final int[] dimensions, final int nElementsPerSource )
	{
		final int nElements = dimensions[ 0 ] * dimensions[ 1 ] * dimensions[ 2 ];
		final int[] data = new int[ nElements ];
		final int listDataSize = 16 * nElements;
		final LongMappedAccessData listData = LongMappedAccessData.factory.createStorage( listDataSize );
		for ( int i = 0, dataIndex = 0; dataIndex < nElements; i += 16, ++dataIndex )
		{
			ByteUtils.putInt( 1, listData.data, i );
			ByteUtils.putLong( 0l, listData.data, i + 4 );
			ByteUtils.putInt( nElementsPerSource, listData.data, i + 12 );
			data[ dataIndex ] = i;
		}
		return new VolatileLabelMultisetArray( data, listData, true );
	}
}
