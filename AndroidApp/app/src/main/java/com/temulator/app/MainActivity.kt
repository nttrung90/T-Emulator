package com.temulator.app

import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.temulator.app.auth.AuthRepository
import com.temulator.app.billing.EntitlementRepository
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val mainRoot = findViewById<LinearLayout>(R.id.mainRoot)
        val fragmentContainer = findViewById<android.view.View>(R.id.fragmentContainer)
        bottomNav = findViewById(R.id.bottomNav)

        val authRepo = AuthRepository.getInstance(this)
        val entitlementRepo = EntitlementRepository.getInstance(this)

        // Refresh entitlement on app startup and on foreground without overlapping coroutines
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (authRepo.isUserLoggedIn()) {
                    entitlementRepo.refreshEntitlement(authRepo)
                }
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { _, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

            // Single owner assignment:
            // 1. Top status bar & cutout: mainRoot handles top padding only
            // 2. IME bottom: if keyboard is open, lift mainRoot by (imeInsets.bottom - systemBars.bottom)
            //    so BottomNav docks cleanly above the keyboard without double counting bottomNav's own system bar padding
            val imeBottomLift = (imeInsets.bottom - systemBars.bottom).coerceAtLeast(0)
            mainRoot.setPadding(0, systemBars.top, 0, imeBottomLift)

            // 3. Left & Right cutouts / side system bars: fragmentContainer owns fragment content horizontal insets
            //    mainRoot leaves left/right at 0 so BottomNavigationView is NOT double-inset
            fragmentContainer.setPadding(systemBars.left, 0, systemBars.right, 0)

            // 4. BottomNavigationView receives insets cleanly to handle its own bottom and horizontal bar padding
            insets
        }

        if (savedInstanceState == null) {
            switchFragment(HomeFragment(), "home")
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    switchFragment(HomeFragment(), "home")
                    true
                }
                R.id.nav_games -> {
                    switchFragment(GamesFragment(), "games")
                    true
                }
                R.id.nav_more -> {
                    switchFragment(MoreFragment(), "more")
                    true
                }
                else -> false
            }
        }

        // When user presses back button, return to Home tab if on another tab
        onBackPressedDispatcher.addCallback(this) {
            if (bottomNav.selectedItemId != R.id.nav_home) {
                bottomNav.selectedItemId = R.id.nav_home
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun switchFragment(fragment: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment, tag)
            .commit()
    }
}
