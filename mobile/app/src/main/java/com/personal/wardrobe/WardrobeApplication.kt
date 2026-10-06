package com.personal.wardrobe

import android.app.Application
import com.personal.wardrobe.data.Repository

class WardrobeApplication:Application(){val repository by lazy{Repository(this)}}
