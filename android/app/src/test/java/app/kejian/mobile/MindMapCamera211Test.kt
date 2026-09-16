package app.kejian.mobile

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class MindMapCamera211Test {
    @Test fun detailPanelResizeNeverChangesUsersCamera(){
        val state=MindMapCameraState()
        state.resized(360f,600f,1,MindMapViewport(.7f,Offset(10f,20f)))
        state.transform(Offset(180f,300f),Offset(28f,-12f),1.8f,.1f)
        val userCamera=state.viewport
        state.resized(360f,360f,1,MindMapViewport(.4f,Offset(50f,60f)))
        assertEquals(userCamera,state.viewport)
        state.resized(360f,600f,1,MindMapViewport(.7f,Offset(10f,20f)))
        assertEquals(userCamera,state.viewport)
    }

    @Test fun rotationPreservesZoomAndWorldCenterWhileExplicitFitCanReset(){
        val state=MindMapCameraState()
        state.resized(360f,600f,1,MindMapViewport(.8f,Offset(20f,30f)))
        state.transform(Offset(100f,200f),Offset(50f,-40f),1.4f,.1f)
        val prior=state.viewport!!
        val worldCenter=Offset(180f,300f)/prior.zoom-prior.pan
        val fitted=MindMapViewport(.6f,Offset(80f,10f))
        state.resized(700f,300f,2,fitted)
        val rotated=state.viewport!!
        assertEquals(prior.zoom,rotated.zoom,0f)
        val nextCenter=Offset(350f,150f)/rotated.zoom-rotated.pan
        assertEquals(worldCenter.x,nextCenter.x,.001f)
        assertEquals(worldCenter.y,nextCenter.y,.001f)
        state.fit(fitted)
        assertEquals(fitted,state.viewport)
    }
}
