/***********************************************************************
 * Copyright (c) 2013-2018 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa.index.index.z2

import com.vividsolutions.jts.geom.Geometry
import org.geotools.factory.Hints
import org.locationtech.geomesa.curve.XZ2SFC
import org.locationtech.geomesa.filter.FilterHelper.fromString
import org.locationtech.geomesa.filter.FilterValues
import org.locationtech.geomesa.index.conf.QueryProperties
import org.locationtech.geomesa.index.geotools.GeoMesaDataStoreFactory.GeoMesaDataStoreConfig
import org.locationtech.geomesa.index.index.IndexKeySpace
import org.locationtech.geomesa.index.index.IndexKeySpace._
import org.locationtech.geomesa.index.utils.Explainer
import org.locationtech.geomesa.utils.geotools.{GeometryUtils, WholeWorldPolygon}
import org.locationtech.geomesa.utils.index.ByteArrays
import org.locationtech.geomesa.utils.text.WKTUtils
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.opengis.filter.Filter

import scala.util.control.NonFatal

object XZ2IndexKeySpace extends XZ2IndexKeySpace

trait XZ2IndexKeySpace extends IndexKeySpace[XZ2IndexValues, Long] {

  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

  override val indexKeyByteLength: Int = 8

  override def supports(sft: SimpleFeatureType): Boolean = sft.nonPoints

  override def toIndexKey(sft: SimpleFeatureType, lenient: Boolean): SimpleFeature => Seq[Long] = {
    val (xBounds, yBounds) = fromString(sft.getZBounds())
    val sfc: XZ2SFC = XZ2SFC(sft.getXZPrecision, xBounds, yBounds)
    val geomIndex = sft.indexOf(sft.getGeometryDescriptor.getLocalName)
    getXZValue(sfc, geomIndex, lenient)
  }

  override def toIndexKeyBytes(sft: SimpleFeatureType, lenient: Boolean): ToIndexKeyBytes = {
    val (xBounds, yBounds) = fromString(sft.getZBounds())
    val sfc: XZ2SFC = XZ2SFC(sft.getXZPrecision, xBounds, yBounds)
    val geomIndex = sft.indexOf(sft.getGeometryDescriptor.getLocalName)
    getXZValueBytes(sfc, geomIndex, lenient)
  }

  override def getIndexValues(sft: SimpleFeatureType, filter: Filter, explain: Explainer): XZ2IndexValues = {
    import org.locationtech.geomesa.filter.FilterHelper._

    val (xBounds, yBounds) = fromString(sft.getZBounds())

    val geometries: FilterValues[Geometry] = {
      val wholePolygon = if (xBounds == (-180, 180) && yBounds == (-90, 90)) {
        null
      } else {
        val geom = s"POLYGON((${xBounds._1} ${yBounds._1}, ${xBounds._1} ${yBounds._2}, " +
          s"${xBounds._2} ${yBounds._2}, ${xBounds._2} ${yBounds._1}, ${xBounds._1} ${yBounds._1}))"
        WKTUtils.read(geom)
      }

      val extracted = extractGeometries(filter, sft.getGeomField, sft.isPoints, wholePolygon)
      if (extracted.nonEmpty) {
        extracted
      } else {
        if (null == wholePolygon) {
          FilterValues(Seq(WholeWorldPolygon))
        } else {
          FilterValues(Seq(wholePolygon))
        }
      }
    }

    explain(s"Geometries: $geometries")

    // compute our ranges based on the coarse bounds for our query

    val sfc = XZ2SFC(sft.getXZPrecision, xBounds, yBounds)
    val xy = geometries.values.map(GeometryUtils.bounds)

    XZ2IndexValues(sfc, geometries, xy)
  }

  override def getRanges(values: XZ2IndexValues): Iterator[ScanRange[Long]] = {
    val XZ2IndexValues(sfc, _, xy) = values
    val zs = sfc.ranges(xy, QueryProperties.ScanRangesTarget.option.map(_.toInt))
    zs.iterator.map(r => BoundedRange(r.lower, r.upper))
  }

  override def getRangeBytes(ranges: Iterator[ScanRange[Long]],
                             prefixes: Seq[Array[Byte]],
                             tier: Boolean): Iterator[ByteRange] = {
    if (prefixes.isEmpty) {
      ranges.map {
        case BoundedRange(lo, hi) => BoundedByteRange(ByteArrays.toBytes(lo), ByteArrays.toBytesFollowingPrefix(hi))
        case r => throw new IllegalArgumentException(s"Unexpected range type $r")
      }
    } else {
      ranges.flatMap {
        case BoundedRange(lo, hi) =>
          val lower = ByteArrays.toBytes(lo)
          val upper = ByteArrays.toBytesFollowingPrefix(hi)
          prefixes.map(p => BoundedByteRange(ByteArrays.concat(p, lower), ByteArrays.concat(p, upper)))

        case r => throw new IllegalArgumentException(s"Unexpected range type $r")
      }
    }
  }

  // always apply the full filter to xz queries
  override def useFullFilter(values: Option[XZ2IndexValues],
                             config: Option[GeoMesaDataStoreConfig],
                             hints: Hints): Boolean = true

  private def getXZValue(sfc: XZ2SFC, geomIndex: Int, lenient: Boolean)(feature: SimpleFeature): Seq[Long] = {
    val geom = feature.getAttribute(geomIndex).asInstanceOf[Geometry]
    if (geom == null) {
      throw new IllegalArgumentException(s"Null geometry in feature ${feature.getID}")
    }
    val envelope = geom.getEnvelopeInternal
    try { Seq(sfc.index(envelope.getMinX, envelope.getMinY, envelope.getMaxX, envelope.getMaxY, lenient)) } catch {
      case NonFatal(e) => throw new IllegalArgumentException(s"Invalid xz value from geometry: $geom", e)
    }
  }

  private def getXZValueBytes(sfc: XZ2SFC,
                              geomIndex: Int,
                              lenient: Boolean)
                             (prefix: Seq[Array[Byte]],
                              feature: SimpleFeature,
                              suffix: Array[Byte]): Seq[Array[Byte]] = {
    val geom = feature.getAttribute(geomIndex).asInstanceOf[Geometry]
    if (geom == null) {
      throw new IllegalArgumentException(s"Null geometry in feature ${feature.getID}")
    }
    val envelope = geom.getEnvelopeInternal

    val xz = try { sfc.index(envelope.getMinX, envelope.getMinY, envelope.getMaxX, envelope.getMaxY, lenient) } catch {
      case NonFatal(e) => throw new IllegalArgumentException(s"Invalid xz value from geometry: $geom", e)
    }

    // create the byte array - allocate a single array up front to contain everything
    val bytes = Array.ofDim[Byte](prefix.map(_.length).sum + 8 + suffix.length)
    var i = 0
    prefix.foreach { p => System.arraycopy(p, 0, bytes, i, p.length); i += p.length }
    ByteArrays.writeLong(xz, bytes, i)
    System.arraycopy(suffix, 0, bytes, i + 8, suffix.length)
    Seq(bytes)
  }
}
