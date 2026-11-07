package com.personal.wardrobe.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the phone's detection geometry to the values the PC's Python implementation produces. */
class SegmentationTest {
    private fun found(result:SegmentationResult):DeviceDetection {
        assertTrue("Expected a detection, got $result",result is SegmentationResult.Found)
        return (result as SegmentationResult.Found).detection
    }

    /** Same synthetic trapezoid used to generate the reference numbers from server/wardrobe/detect.py. */
    private fun trapezoid():Triple<IntArray,IntArray,IntArray> {
        val width=100;val height=200
        val frame=IntArray(width*height){0xFFF0F0F0.toInt()}
        val alpha=IntArray(width*height)
        for(y in 20..180){
            val half=when{y<=60->16+(y-20)/5;y<=120->24+(y-60)/6;else->34-(y-120)/8}
            val x0=50-half;val x1=50+half
            val split=x0+(x1-x0)*3/5
            for(x in x0 until x1){
                alpha[y*width+x]=255
                frame[y*width+x]=if(x<split)0xFF3D6699.toInt() else 0xFF6B4A2F.toInt()
            }
        }
        return Triple(frame,alpha,intArrayOf(width,height))
    }

    @Test fun readsTheSameSilhouetteAsThePc() {
        val (frame,alpha,size)=trapezoid()
        val detection=found(Segmentation.analyze(frame,size[0],size[1],alpha))
        assertEquals(16,detection.left);assertEquals(20,detection.top)
        assertEquals(84,detection.right);assertEquals(181,detection.bottom)
        assertEquals(161,detection.metrics.height);assertEquals(68,detection.metrics.width)
        assertEquals(2.368,detection.metrics.aspect,1e-9)
        assertEquals(0.797,detection.metrics.fill,1e-9)
        assertEquals(0.938,detection.metrics.flare,1e-9)
        assertEquals(0.643,detection.metrics.shoulder,1e-9)
        assertEquals(8722,detection.metrics.foregroundPixels)
        assertEquals(0.436,detection.areaShare,1e-9)
    }

    @Test fun suggestsTypeShapeAndColoursFromTheMaskOnly() {
        val (frame,alpha,size)=trapezoid()
        val detection=found(Segmentation.analyze(frame,size[0],size[1],alpha))
        assertEquals("onepiece",detection.category)
        assertEquals("Dress",detection.subcategory)
        assertEquals("longline",detection.shape)
        assertEquals("relaxed",detection.fit)
        assertEquals(0.38,detection.confidence,1e-9)
        assertEquals("#3d6699",detection.color)
        assertEquals(listOf("#6b4a2f"),detection.secondaryColors)
    }

    @Test fun aWideBlockReadsAsAFullOnepiece() {
        val width=100;val height=200
        val frame=IntArray(width*height){0xFF222222.toInt()}
        val alpha=IntArray(width*height)
        for(y in 10 until 190)for(x in 5 until 95){alpha[y*width+x]=255;frame[y*width+x]=0xFFCC3344.toInt()}
        val detection=found(Segmentation.analyze(frame,width,height,alpha))
        assertEquals(5,detection.left);assertEquals(10,detection.top)
        assertEquals(95,detection.right);assertEquals(190,detection.bottom)
        assertEquals(2.0,detection.metrics.aspect,1e-9)
        assertEquals(1.0,detection.metrics.fill,1e-9)
        assertEquals(1.0,detection.metrics.shoulder,1e-9)
        assertEquals(16200,detection.metrics.foregroundPixels)
        assertEquals("onepiece",detection.category)
        assertEquals("full silhouette",detection.shape)
        assertEquals("oversized",detection.fit)
        assertEquals(0.44,detection.confidence,1e-9)
        assertEquals("#cc3344",detection.color)
        assertTrue(detection.secondaryColors.isEmpty())
    }

    @Test fun anEmptyMaskIsReportedInsteadOfGuessed() {
        val frame=IntArray(100*200){0xFFF0F0F0.toInt()}
        assertNull(Segmentation.trim(IntArray(100*200),100,200))
        assertEquals(SegmentationResult.NoGarment,Segmentation.analyze(frame,100,200,IntArray(100*200)))
    }

    @Test fun theModelMaskIsStretchedToTheFullRange() {
        assertEquals(listOf(0,127,255),Segmentation.mask(floatArrayOf(-1f,0f,1f)).toList())
    }

    @Test fun theInputMatchesThePcNormalisation() {
        val pixels=IntArray(Segmentation.SIDE*Segmentation.SIDE){0xFF808080.toInt()}
        val input=Segmentation.input(pixels)
        val plane=Segmentation.SIDE*Segmentation.SIDE
        assertEquals(3*plane,input.size)
        assertEquals(0.0740646f,input[0],1e-4f)
        assertEquals(0.205182f,input[plane],1e-4f)
        assertEquals(0.426492f,input[2*plane],1e-4f)
        assertEquals(-2.117904f,Segmentation.input(IntArray(Segmentation.SIDE*Segmentation.SIDE))[0],1e-4f)
    }

    @Test fun resizingKeepsTheMaskInRange() {
        val mask=intArrayOf(0,255,255,0)
        val resized=Segmentation.resizeMask(mask,2,2,5,7)
        assertEquals(35,resized.size)
        assertTrue(resized.all{it in 0..255})
        assertEquals(0,resized.first())
        assertEquals(0,resized.last())
    }

    @Test fun coloursNeverComeFromOutsideTheMask() {
        val width=20;val height=20
        val frame=IntArray(width*height){0xFFFF0000.toInt()}
        val alpha=IntArray(width*height)
        for(y in 5 until 15)for(x in 5 until 15){alpha[y*width+x]=255;frame[y*width+x]=0xFF2244AA.toInt()}
        val detection=found(Segmentation.analyze(frame,width,height,alpha))
        assertEquals("#2244aa",detection.color)
        assertEquals(listOf(5,5,15,15),listOf(detection.left,detection.top,detection.right,detection.bottom))
    }
}
