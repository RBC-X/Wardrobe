package com.personal.wardrobe.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/** Silhouette measurements from the clothing mask alone; widths are relative to the widest row. */
data class Silhouette(
    val height:Int, val width:Int, val aspect:Double, val fill:Double,
    val flare:Double, val shoulder:Double, val foregroundPixels:Int
)

/** One background-free reading of a garment. Every field is a suggestion the owner can correct. */
data class DeviceDetection(
    val category:String, val subcategory:String, val color:String, val secondaryColors:List<String>,
    val shape:String, val fit:String, val confidence:Double, val metrics:Silhouette,
    val left:Int, val top:Int, val right:Int, val bottom:Int,
    val frameWidth:Int, val frameHeight:Int, val areaShare:Double
)

sealed class SegmentationResult {
    data class Found(val detection:DeviceDetection):SegmentationResult()
    object NoGarment:SegmentationResult()
    object NoColours:SegmentationResult()
}

/**
 * Background-free detection with no PC and no network.
 *
 * This is a pure Kotlin port of `server/wardrobe/detect.py`, so the phone reaches the same kind of
 * suggestions from the same kind of mask: the bundled U²-Net model supplies the soft mask, and colour,
 * type and shape are then read only from pixels inside that mask. A floor or wall can therefore never be
 * reported as part of the garment. The Android side only decodes the photo and runs the model.
 */
object Segmentation {
    const val SIDE=320
    private const val TRIM=16
    private val MEAN=floatArrayOf(.485f,.456f,.406f)
    private val STD=floatArrayOf(.229f,.224f,.225f)

    /** Bilinear resize of packed ARGB pixels. Alpha is interpolated too, so cutout edges stay soft. */
    fun resize(source:IntArray,sourceWidth:Int,sourceHeight:Int,width:Int,height:Int):IntArray {
        require(source.size==sourceWidth*sourceHeight){"Source size does not match its pixels"}
        if(sourceWidth==width&&sourceHeight==height)return source.copyOf()
        val output=IntArray(width*height)
        for(y in 0 until height){
            val sy=((y+.5)*sourceHeight/height-.5).coerceIn(0.0,(sourceHeight-1).toDouble())
            val y0=sy.toInt();val y1=min(y0+1,sourceHeight-1);val fy=sy-y0
            for(x in 0 until width){
                val sx=((x+.5)*sourceWidth/width-.5).coerceIn(0.0,(sourceWidth-1).toDouble())
                val x0=sx.toInt();val x1=min(x0+1,sourceWidth-1);val fx=sx-x0
                val a=source[y0*sourceWidth+x0];val b=source[y0*sourceWidth+x1]
                val c=source[y1*sourceWidth+x0];val d=source[y1*sourceWidth+x1]
                var packed=0
                for(shift in intArrayOf(24,16,8,0)){
                    val top=((a ushr shift) and 0xFF)*(1-fx)+((b ushr shift) and 0xFF)*fx
                    val bottom=((c ushr shift) and 0xFF)*(1-fx)+((d ushr shift) and 0xFF)*fx
                    packed=packed or (round(top*(1-fy)+bottom*fy).toInt().coerceIn(0,255) shl shift)
                }
                output[y*width+x]=packed
            }
        }
        return output
    }

    /** Model input: 320×320 RGB, /255 then ImageNet normalization, in NCHW order. */
    fun input(pixels:IntArray,size:Int=SIDE):FloatArray {
        require(pixels.size==size*size){"Expected a $size×$size image"}
        val plane=size*size
        val output=FloatArray(plane*3)
        for(index in 0 until plane){
            val pixel=pixels[index]
            output[index]=((((pixel shr 16) and 0xFF)/255f)-MEAN[0])/STD[0]
            output[plane+index]=((((pixel shr 8) and 0xFF)/255f)-MEAN[1])/STD[1]
            output[plane*2+index]=(((pixel and 0xFF)/255f)-MEAN[2])/STD[2]
        }
        return output
    }

    /** Min-max stretch of the raw prediction to 0–255, as the PC does before resizing it. */
    fun mask(prediction:FloatArray):IntArray {
        require(prediction.isNotEmpty()){"The model returned no mask"}
        var low=Float.MAX_VALUE;var high=-Float.MAX_VALUE
        for(value in prediction){if(value<low)low=value;if(value>high)high=value}
        val span=max(high-low,1e-6f)
        return IntArray(prediction.size){index->(((prediction[index]-low)/span)*255f).toInt().coerceIn(0,255)}
    }

    /** Bilinear resize of a 0–255 mask to the photo size. */
    fun resizeMask(mask:IntArray,sourceWidth:Int,sourceHeight:Int,width:Int,height:Int):IntArray {
        require(mask.size==sourceWidth*sourceHeight){"Mask size does not match the model output"}
        if(sourceWidth==width&&sourceHeight==height)return mask.copyOf()
        val output=IntArray(width*height)
        for(y in 0 until height){
            val sy=((y+.5)*sourceHeight/height-.5).coerceIn(0.0,(sourceHeight-1).toDouble())
            val y0=sy.toInt();val y1=min(y0+1,sourceHeight-1);val fy=sy-y0
            for(x in 0 until width){
                val sx=((x+.5)*sourceWidth/width-.5).coerceIn(0.0,(sourceWidth-1).toDouble())
                val x0=sx.toInt();val x1=min(x0+1,sourceWidth-1);val fx=sx-x0
                val top=mask[y0*sourceWidth+x0]*(1-fx)+mask[y0*sourceWidth+x1]*fx
                val bottom=mask[y1*sourceWidth+x0]*(1-fx)+mask[y1*sourceWidth+x1]*fx
                output[y*width+x]=round(top*(1-fy)+bottom*fy).toInt().coerceIn(0,255)
            }
        }
        return output
    }

    fun silhouette(alpha:IntArray,width:Int,height:Int):Silhouette? {
        require(alpha.size==width*height){"Mask size does not match the photo"}
        val counts=IntArray(height);var top=-1;var bottom=-1;var left=width;var right=-1
        for(y in 0 until height){
            var count=0
            val row=y*width
            for(x in 0 until width){
                val value=alpha[row+x]
                if(value>=128){count++;if(x<left)left=x;if(x>right)right=x}
            }
            counts[y]=count
            if(count>0){if(top<0)top=y;bottom=y}
        }
        if(top<0||right<0)return null
        val cropped=counts.copyOfRange(top,bottom+1)
        val tallest=max(1,cropped.max())
        fun band(start:Double,end:Double):Double {
            val from=(cropped.size*start).toInt().coerceIn(0,cropped.size)
            val to=(cropped.size*end).toInt().coerceAtLeast(1).coerceIn(from,cropped.size)
            if(from>=to)return 0.0
            var sum=0.0
            for(index in from until to)sum+=cropped[index]
            return (sum/(to-from))/tallest
        }
        val middle=band(.40,.60)
        var foreground=0
        for(index in top..bottom)foreground+=counts[index]
        return Silhouette(
            height=cropped.size, width=right-left+1,
            aspect=round3(cropped.size.toDouble()/max(right-left+1,1)),
            fill=round3(band(0.0,1.0)),
            flare=round3(band(.86,1.0)/max(middle,1e-6)),
            shoulder=round3(band(.10,.30)),
            foregroundPixels=foreground
        )
    }

    /** Colours read only from pixels inside the clothing mask, merged across shading (as on the PC). */
    fun dominantColors(frame:IntArray,alpha:IntArray,width:Int,height:Int,left:Int,top:Int,right:Int,bottom:Int,limit:Int=3):List<String> {
        var minimum=0
        for(candidate in intArrayOf(200,64,16)){
            var count=0
            for(y in top until bottom)for(x in left until right)if(alpha[y*width+x]>=candidate)count++
            if(count>=64){minimum=candidate;break}
        }
        if(minimum==0)return emptyList()
        val counts=HashMap<Int,Int>();val sums=HashMap<Int,DoubleArray>()
        for(y in top until bottom){
            val row=y*width
            for(x in left until right){
                if(alpha[row+x]<minimum)continue
                val pixel=frame[row+x]
                val r=(pixel shr 16 and 0xFF).toDouble();val g=(pixel shr 8 and 0xFF).toDouble();val b=(pixel and 0xFF).toDouble()
                val key=((((r/24).toInt()*24+12)) shl 16) or ((((g/24).toInt()*24+12)) shl 8) or (((b/24).toInt()*24+12))
                counts[key]=(counts[key]?:0)+1
                val sum=sums.getOrPut(key){DoubleArray(3)}
                sum[0]+=r;sum[1]+=g;sum[2]+=b
            }
        }
        val ordered=counts.keys.sortedWith(compareByDescending<Int>{counts[it]!!}.thenBy{it})
        val colors=mutableListOf<String>()
        for(key in ordered){
            val count=counts[key]!!;val sum=sums[key]!!
            val value=hex(sum[0]/count,sum[1]/count,sum[2]/count)
            if(colors.all{separation(value,it)>=36})colors.add(value)
            if(colors.size>=limit)break
        }
        return colors
    }

    /** Bounding box of everything the mask calls clothing, padded by nothing but the model's own edge. */
    fun trim(alpha:IntArray,width:Int,height:Int,threshold:Int=TRIM):IntArray? {
        require(alpha.size==width*height){"Mask size does not match the photo"}
        var left=width;var top=height;var right=-1;var bottom=-1
        for(y in 0 until height)for(x in 0 until width){
            if(alpha[y*width+x]>=threshold){if(x<left)left=x;if(x>right)right=x;if(y<top)top=y;if(y>bottom)bottom=y}
        }
        if(right<0||bottom<0)return null
        return intArrayOf(left,top,right+1,bottom+1)
    }

    fun analyze(frame:IntArray,width:Int,height:Int,alpha:IntArray):SegmentationResult {
        require(frame.size==width*height&&alpha.size==width*height){"Photo size does not match its mask"}
        val box=trim(alpha,width,height)?:return SegmentationResult.NoGarment
        val metrics=silhouette(alpha,width,height)?:return SegmentationResult.NoGarment
        val colors=dominantColors(frame,alpha,width,height,box[0],box[1],box[2],box[3])
        if(colors.isEmpty())return SegmentationResult.NoColours
        val areaShare=metrics.foregroundPixels.toDouble()/max(1,width*height)
        val category=bestCategory(metrics,areaShare)
        return SegmentationResult.Found(DeviceDetection(
            category=category.first,
            subcategory=subtype(category.first,metrics),
            color=colors.first(),
            secondaryColors=colors.drop(1).take(2),
            shape=shape(metrics),
            fit=guessFit(metrics),
            confidence=category.second,
            metrics=metrics,
            left=box[0],top=box[1],right=box[2],bottom=box[3],
            frameWidth=width,frameHeight=height,areaShare=round3(areaShare)
        ))
    }

    private fun near(value:Double,target:Double,tolerance:Double)=max(0.0,1.0-abs(value-target)/tolerance)

    /** Explicit, inspectable evidence per category; ties break alphabetically for determinism. */
    private fun scores(metrics:Silhouette,areaShare:Double):Map<String,Double> {
        val aspect=metrics.aspect;val fill=metrics.fill;val flare=metrics.flare;val shoulder=metrics.shoulder
        return linkedMapOf(
            "accessory" to 0.9*near(areaShare,.02,.10)+0.5*near(aspect,1.2,1.0)+0.2*near(fill,.7,.5),
            "shoes" to 0.9*near(aspect,.55,.45)+0.7*near(fill,.85,.35)+0.3*near(areaShare,.35,.45),
            "top" to 0.9*near(aspect,1.15,.55)+0.5*near(fill,.75,.35)+0.3*near(flare,1.0,.45),
            "bottom" to 0.8*near(aspect,1.85,.8)+0.5*near(shoulder,.72,.4)+0.4*near(fill,.65,.4),
            "onepiece" to 0.8*near(aspect,2.15,.95)+0.5*near(flare,1.3,.55)+0.3*near(fill,.7,.45),
            "outerwear" to 0.6*near(aspect,1.5,.7)+0.6*near(fill,.87,.28)+0.3*near(shoulder,.95,.3)
        )
    }

    private fun bestCategory(metrics:Silhouette,areaShare:Double):Pair<String,Double> {
        val ranked=scores(metrics,areaShare).entries.sortedWith(compareByDescending<Map.Entry<String,Double>>{it.value}.thenBy{it.key})
        val best=ranked.first();val second=ranked.getOrNull(1)?.value?:0.0
        val margin=(best.value-second)/max(best.value,1e-6)
        val quality=min(1.0,metrics.foregroundPixels/40_000.0)
        return best.key to round2(min(.85,max(.30,.32+.45*margin+.12*quality)))
    }

    /** Only suggest a subtype when the outline genuinely narrows it down. */
    private fun subtype(category:String,metrics:Silhouette):String {
        if(category=="bottom"&&metrics.aspect<1.05)return "Shorts"
        if(category=="onepiece")return "Dress"
        return ""
    }

    private fun shape(metrics:Silhouette):String {
        val parts=mutableListOf<String>()
        if(metrics.aspect>=2.2)parts.add("longline") else if(metrics.aspect<=.85)parts.add("compact")
        if(metrics.flare>=1.22)parts.add("flared hem") else if(metrics.flare<=.82)parts.add("tapered")
        if(metrics.shoulder<=.62)parts.add("narrow shoulder")
        if(metrics.fill>=.85)parts.add("full silhouette") else if(metrics.fill<=.52)parts.add("slim silhouette")
        return if(parts.isEmpty())"straight silhouette" else parts.take(3).joinToString(", ")
    }

    private fun guessFit(metrics:Silhouette)=when{metrics.fill>=.90->"oversized";metrics.fill>=.74->"relaxed";metrics.fill<=.45->"slim";else->"regular"}

    private fun hex(red:Double,green:Double,blue:Double)="#%02x%02x%02x".format(
        round(red).toInt().coerceIn(0,255),round(green).toInt().coerceIn(0,255),round(blue).toInt().coerceIn(0,255)
    )

    private fun separation(first:String,second:String):Double {
        var total=0.0
        for(index in listOf(1,3,5)){
            val difference=(first.substring(index,index+2).toInt(16)-second.substring(index,index+2).toInt(16)).toDouble()
            total+=difference*difference
        }
        return sqrt(total)
    }

    private fun round3(value:Double)=round(value*1000)/1000.0
    private fun round2(value:Double)=round(value*100)/100.0
}
