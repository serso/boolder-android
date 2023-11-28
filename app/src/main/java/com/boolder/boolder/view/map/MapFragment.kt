package com.boolder.boolder.view.map

import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.postDelayed
import androidx.core.view.updateLayoutParams
import androidx.core.view.updateMargins
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.boolder.boolder.R
import com.boolder.boolder.databinding.FragmentMapBinding
import com.boolder.boolder.domain.model.Area
import com.boolder.boolder.domain.model.Circuit
import com.boolder.boolder.domain.model.GradeRange
import com.boolder.boolder.domain.model.Problem
import com.boolder.boolder.domain.model.Topo
import com.boolder.boolder.domain.model.TopoOrigin
import com.boolder.boolder.utils.LocationProvider
import com.boolder.boolder.utils.MapboxStyleFactory
import com.boolder.boolder.utils.extension.launchAndCollectIn
import com.boolder.boolder.view.compose.BoolderTheme
import com.boolder.boolder.view.map.BoolderMap.BoolderMapListener
import com.boolder.boolder.view.map.animator.animationEndListener
import com.boolder.boolder.view.map.composable.MapControlsOverlay
import com.boolder.boolder.view.map.filter.circuit.CircuitFilterBottomSheetDialogFragment
import com.boolder.boolder.view.map.filter.circuit.CircuitFilterBottomSheetDialogFragment.Companion.RESULT_CIRCUIT
import com.boolder.boolder.view.map.filter.grade.GradesFilterBottomSheetDialogFragment
import com.boolder.boolder.view.map.filter.grade.GradesFilterBottomSheetDialogFragment.Companion.RESULT_GRADE_RANGE
import com.boolder.boolder.view.search.SearchFragment
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
import com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mapbox.geojson.Geometry
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.locationcomponent.location
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.lang.Double.max


class MapFragment : Fragment(), BoolderMapListener {

    private var binding: FragmentMapBinding? = null

    private val mapViewModel by viewModel<MapViewModel>()
    private val layerFactory by inject<MapboxStyleFactory>()

    private lateinit var locationProvider: LocationProvider

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationProvider = LocationProvider(requireActivity())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        FragmentMapBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomMargin = systemInsets.bottom + resources.getDimensionPixelSize(R.dimen.margin_map_controls)

            binding.mapView.applyInsets(systemInsets)

            binding.fabLocation
                .updateLayoutParams<MarginLayoutParams> { updateMargins(bottom = bottomMargin) }

            insets
        }

        locationProvider.locationFlow.launchAndCollectIn(owner = this, collector = ::onGPSLocation)

        bottomSheetBehavior = BottomSheetBehavior.from(binding.detailBottomSheet).also {
            it.skipCollapsed = true
            it.state = STATE_HIDDEN
            it.addBottomSheetCallback(object : BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    when (newState) {
                        STATE_EXPANDED -> mapViewModel.onProblemTopoVisibilityChanged(isVisible = true)
                        STATE_HIDDEN -> mapViewModel.onProblemTopoVisibilityChanged(isVisible = false)
                        else -> Unit
                    }
                }

                override fun onSlide(bottomSheet: View, slideOffset: Float) {}
            })
        }

        setupMap()

        binding.fabLocation.setOnClickListener {
            locationProvider.askForPosition()
        }

//        binding.offlinePhotosButton.setOnClickListener {
//            findNavController().navigate(MapFragmentDirections.navigateToOfflinePhotosScreen())
//        }

        binding.topoView.apply {
            onSelectProblemOnMap = { problemId ->
                binding.mapView.selectProblem(problemId)
                mapViewModel.updateCircuitControlsForProblem(problemId)
            }
            onCircuitProblemSelected = {
                mapViewModel.fetchTopo(problemId = it, origin = TopoOrigin.CIRCUIT)
            }
        }

        mapViewModel.topoStateFlow.launchAndCollectIn(owner = this, collector = ::onNewTopo)

        mapViewModel.screenStateFlow.launchAndCollectIn(owner = this) { screenState ->
            binding.controlsOverlayComposeView.setContent {
                BoolderTheme {
                    MapControlsOverlay(
                        offlineAreaItem = screenState.areaState,
                        circuitState = screenState.circuitState,
                        gradeState = screenState.gradeState,
                        popularState = screenState.popularFilterState,
                        shouldShowFiltersBar = screenState.shouldShowFiltersBar,
                        offlineAreaDownloader = mapViewModel,
                        onHideAreaName = ::onAreaLeft,
                        onSearchBarClicked = ::navigateToSearchScreen,
                        onCircuitFilterChipClicked = mapViewModel::onCircuitFilterChipClicked,
                        onGradeFilterChipClicked = mapViewModel::onGradeFilterChipClicked,
                        onPopularFilterChipClicked = mapViewModel::onPopularFilterChipClicked,
                        onResetFiltersClicked = mapViewModel::onResetFiltersButtonClicked,
                        onCircuitStartClicked = mapViewModel::onCircuitDepartureButtonClicked
                    )
                }
            }

            binding.mapView.apply {
                updateCircuit(screenState.circuitState?.circuitId?.toLong())
                applyFilters(
                    grades = screenState.gradeState.grades,
                    showPopular = screenState.popularFilterState.isEnabled
                )
            }
        }

        mapViewModel.eventFlow.launchAndCollectIn(owner = this) { event ->
            when (event) {
                is MapViewModel.Event.ShowAvailableCircuits -> showCircuitFilterBottomSheet(event)
                is MapViewModel.Event.ShowGradeRanges -> showGradesFilterBottomSheet(event)
                is MapViewModel.Event.ZoomOnCircuit -> zoomOnCircuit(event)
                is MapViewModel.Event.ZoomOnCircuitStartProblem -> onProblemSelected(event.problemId, TopoOrigin.CIRCUIT)
            }
        }

        parentFragmentManager.setFragmentResultListener(
            /* requestKey = */ SearchFragment.REQUEST_KEY,
            /* lifecycleOwner = */ this
        ) { _, bundle ->
            when {
                bundle.containsKey("AREA") -> binding.root.postDelayed(500L) {
                    flyToArea(requireNotNull(bundle.getParcelable("AREA")))
                }

                bundle.containsKey("PROBLEM") -> onProblemSelected(
                    problemId = requireNotNull(bundle.getParcelable<Problem>("PROBLEM")).id,
                    origin = TopoOrigin.SEARCH
                )
            }
        }

        parentFragmentManager.setFragmentResultListener(
            /* requestKey = */ CircuitFilterBottomSheetDialogFragment.REQUEST_KEY,
            /* lifecycleOwner = */ this
        ) { _, bundle ->
            val circuit = bundle.getParcelable<Circuit?>(RESULT_CIRCUIT)

            mapViewModel.onCircuitSelected(circuit)
        }

        parentFragmentManager.setFragmentResultListener(
            /* requestKey = */ GradesFilterBottomSheetDialogFragment.REQUEST_KEY,
            /* lifecycleOwner = */ this
        ) { _, bundle ->
            val gradeRange = requireNotNull(bundle.getParcelable<GradeRange>(RESULT_GRADE_RANGE))

            mapViewModel.onGradeRangeSelected(gradeRange)
        }
    }

    override fun onDestroyView() {
        binding?.topoView?.apply {
            onSelectProblemOnMap = null
            onCircuitProblemSelected = null
        }

        binding = null

        super.onDestroyView()
    }

    private fun onGPSLocation(location: Location) {
        val binding = binding ?: return

        val point = Point.fromLngLat(location.longitude, location.latitude)
        val zoomLevel = max(binding.mapView.getMapboxMap().cameraState.zoom, 17.0)

        binding.mapView.getMapboxMap()
            .setCamera(CameraOptions.Builder().center(point).zoom(zoomLevel).bearing(location.bearing.toDouble()).build())
        binding.mapView.location.updateSettings {
            enabled = true
            pulsingEnabled = true
        }
    }

    private fun setupMap() {
        binding?.mapView?.setup(this, layerFactory.buildStyle())
    }

    // Triggered when user click on a Problem on Map
    override fun onProblemSelected(problemId: Int, origin: TopoOrigin) {
        mapViewModel.fetchTopo(problemId = problemId, origin = origin)
    }

    override fun onProblemUnselected() {
        bottomSheetBehavior.state = STATE_HIDDEN
    }

    override fun onPoisSelected(poisName: String, stringProperty: String, geometry: Geometry?) {
        val context = context ?: return
        val binding = binding ?: return

        val view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_pois, binding.root, false)
        val bottomSheet = BottomSheetDialog(context, R.style.BottomSheetDialogTheme)

        view.apply {
            findViewById<TextView>(R.id.pois_title).text = poisName
            findViewById<Button>(R.id.open).setOnClickListener {
                openGoogleMaps(stringProperty)
            }
            findViewById<Button>(R.id.close).setOnClickListener { bottomSheet.dismiss() }
        }
        bottomSheet.setContentView(view)
        bottomSheet.show()
    }

    override fun onAreaVisited(areaId: Int) {
        mapViewModel.onAreaVisited(areaId)
    }

    override fun onAreaLeft() {
        mapViewModel.onAreaLeft()
    }

    override fun onZoomLevelChanged(zoomLevel: Double) {
        mapViewModel.onZoomLevelChanged(zoomLevel)
    }

    private fun onNewTopo(nullableTopo: Topo?) {
        nullableTopo?.let { topo ->
            val binding = binding ?: return

            binding.topoView.setTopo(topo)

            val selectedProblem = topo.selectedCompleteProblem
                ?.problemWithLine
                ?.problem
                ?: return@let

            flyToProblem(problem = selectedProblem, origin = topo.origin)
        }
        bottomSheetBehavior.state = if (nullableTopo == null) STATE_HIDDEN else STATE_EXPANDED
    }

    private fun openGoogleMaps(url: String) {

        val sendIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val shareIntent = Intent.createChooser(sendIntent, null)
        try {
            startActivity(shareIntent)
        } catch (e: Exception) {
            Log.i("MAP", "No apps can handle this kind of intent")
        }
    }

    private fun flyToArea(area: Area) {
        val binding = binding ?: return

        val southWest = Point.fromLngLat(
            area.southWestLon.toDouble(),
            area.southWestLat.toDouble()
        )
        val northEst = Point.fromLngLat(
            area.northEastLon.toDouble(),
            area.northEastLat.toDouble()
        )
        val coordinates = CoordinateBounds(southWest, northEst)

        val cameraOptions = binding.mapView.getMapboxMap().cameraForCoordinateBounds(
            coordinates,
            EdgeInsets(60.0, 8.0, 8.0, 8.0),
            0.0,
            0.0
        )

        binding.mapView.camera.flyTo(
            cameraOptions = cameraOptions,
            animationOptions = defaultMapAnimationOptions {
                animatorListener(animationEndListener { onAreaVisited(area.id) })
            }
        )

        bottomSheetBehavior.state = STATE_HIDDEN
    }

    private fun flyToProblem(problem: Problem, origin: TopoOrigin) {
        val binding = binding ?: return

        binding.mapView.selectProblem(problem.id.toString())

        val point = Point.fromLngLat(
            problem.longitude.toDouble(),
            problem.latitude.toDouble()
        )

        val zoomLevel = binding.mapView.getMapboxMap().cameraState.zoom

        val cameraOptions = CameraOptions.Builder().run {
            if (origin in arrayOf(TopoOrigin.SEARCH, TopoOrigin.CIRCUIT)) center(point)

            padding(EdgeInsets(40.0, 0.0, binding.mapView.height / 2.0, 0.0))
            zoom(if (zoomLevel <= 19.0) 20.0 else zoomLevel)
            build()
        }

        binding.mapView.camera.easeTo(
            cameraOptions = cameraOptions,
            animationOptions = defaultMapAnimationOptions {
                animatorListener(animationEndListener { onAreaVisited(problem.areaId) })
            }
        )
    }

    private fun defaultMapAnimationOptions(block: MapAnimationOptions.Builder.() -> Unit) =
        MapAnimationOptions.mapAnimationOptions {
            duration(300L)
            interpolator(AccelerateDecelerateInterpolator())
            block()
        }

    private fun navigateToSearchScreen() {
        findNavController().navigate(MapFragmentDirections.navigateToSearch())
    }

    private fun showCircuitFilterBottomSheet(event: MapViewModel.Event.ShowAvailableCircuits) {
        val navController = findNavController()

        if (navController.currentDestination?.id == R.id.dialog_circuit_filter) return

        val direction = MapFragmentDirections.showCircuitsFilter(
            availableCircuits = event.availableCircuits.toTypedArray()
        )

        navController.navigate(direction)
    }

    private fun showGradesFilterBottomSheet(event: MapViewModel.Event.ShowGradeRanges) {
        val navController = findNavController()

        if (navController.currentDestination?.id == R.id.dialog_grades_filter) return

        val direction = MapFragmentDirections.showGradesFilter(gradeRange = event.currentGradeRange)

        navController.navigate(direction)
    }

    private fun zoomOnCircuit(event: MapViewModel.Event.ZoomOnCircuit) {
        val binding = binding ?: return

        bottomSheetBehavior.state = STATE_HIDDEN
        binding.mapView.onCircuitSelected(event.circuit)
    }
}