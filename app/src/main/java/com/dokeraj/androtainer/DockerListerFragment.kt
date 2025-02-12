package com.dokeraj.androtainer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.dokeraj.androtainer.adapter.DockerContainerAdapter
import com.dokeraj.androtainer.adapter.DockerEndpointAdapter
import com.dokeraj.androtainer.globalvars.GlobalApp
import com.dokeraj.androtainer.models.ContainerStateType
import com.dokeraj.androtainer.models.Kontainer
import com.dokeraj.androtainer.util.DataState
import com.dokeraj.androtainer.viewmodels.DockerListerViewModel
import com.dokeraj.androtainer.viewmodels.MainStateEvent
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.android.synthetic.main.drawer_lister_header.*
import kotlinx.android.synthetic.main.fragment_docker_lister.*
import kotlinx.coroutines.ExperimentalCoroutinesApi

@AndroidEntryPoint
class DockerListerFragment : Fragment(R.layout.fragment_docker_lister) {
    private val args: DockerListerFragmentArgs by navArgs()
    private var lastTimePressed: Long = 0L
    private val intervalToastTime = 1200

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        /** how to instantiate a viewModel object*/
        val model: DockerListerViewModel =
            ViewModelProvider(requireActivity()).get(DockerListerViewModel::class.java)

        requireActivity().window.statusBarColor =
            ContextCompat.getColor(requireContext(), R.color.dis2)

        val globActivity: MainActiviy = (activity as MainActiviy?)!!
        val globalVars: GlobalApp = (globActivity.application as GlobalApp)

        tvContainerListerEndpointName.text = globalVars.currentUser!!.currentEndpoint.name

        setDrawerInfo(globalVars)

        val hamburgerMenu = ActionBarDrawerToggle(activity,
            drawerLister,
            toolbarMenu,
            R.string.nav_app_bar_open_drawer_description,
            R.string.navigation_drawer_close)

        hamburgerMenu.drawerArrowDrawable.color =
            ContextCompat.getColor(requireContext(), R.color.disText2)
        drawerLister.addDrawerListener(hamburgerMenu)
        hamburgerMenu.syncState()

        if (globActivity.getIsLoginToDockerLister()) {
            globActivity.setIsLoginToDockerLister(false)
            model.setStateEvent(MainStateEvent.InitializeView(args.dContainers.containers))
        }

        // just give an empty list of containers when initializing the recyclerAdapter
        // we will fill the adapter when the modelview is initialized
        val containers: List<Kontainer> = listOf()

        val recyclerAdapter =
            DockerContainerAdapter(containers,
                globalVars.currentUser!!.serverUrl,
                globalVars.currentUser!!.jwt!!,
                globalVars.currentUser!!.currentEndpoint.id,
                requireContext(), this, model)
        recycler_view.adapter = recyclerAdapter
        recycler_view.layoutManager = LinearLayoutManager(activity)
        recycler_view.setHasFixedSize(true)

        rvDockerEndpoints.adapter = DockerEndpointAdapter(globalVars.currentUser!!.listOfEndpoints,
            globalVars, requireContext(), globActivity, drawerLister, model, recyclerAdapter, this)
        rvDockerEndpoints.layoutManager = LinearLayoutManager(activity)
        rvDockerEndpoints.setHasFixedSize(true)

        btnLogout.setOnClickListener {
            logout(globActivity)
        }

        btnAbout.setOnClickListener {
            if (tvAboutInfo.visibility == View.VISIBLE) {
                tvAboutInfo.visibility = View.INVISIBLE
                btnDonate.visibility = View.INVISIBLE
            } else {
                tvAboutInfo.visibility = View.VISIBLE
                btnDonate.visibility = View.VISIBLE
            }
        }

        btnDonate.setOnClickListener {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://donate.dokeraj.cc"))
            startActivity(i)
        }

        btnEndpoints.setOnClickListener {
            if (rvDockerEndpoints.visibility == View.VISIBLE)
                rvDockerEndpoints.visibility = View.GONE
            else {
                rvDockerEndpoints.visibility = View.VISIBLE
            }
        }

        btnManageUsers.setOnClickListener {
            val action =
                DockerListerFragmentDirections.actionDockerListerFragmentToUsersListerFragment()
            findNavController().navigate(action)
        }

        swiperLayout.setOnRefreshListener {
            callSwiperLogic(model, globActivity, globalVars, recyclerAdapter)
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, true) {
            // hijack the back button press and don't allow going back to login page (only close the drawer)
            if (drawerLister.isDrawerOpen(GravityCompat.START))
                drawerLister.close()
            else {
                if (lastTimePressed < System.currentTimeMillis() - intervalToastTime) {
                    globActivity.showGenericSnack(requireContext(),
                        requireView(),
                        "Press back again to close the app",
                        R.color.blue_main,
                        R.color.dis2, intervalToastTime)
                    lastTimePressed = System.currentTimeMillis()
                } else
                    globActivity.moveTaskToBack(true)
            }
        }

        subscribeObservers(model, recyclerAdapter, globActivity)
    }

    private fun subscribeObservers(
        dataViewModel: DockerListerViewModel,
        recyclerAdapter: DockerContainerAdapter,
        mainActivity: MainActiviy,
    ) {
        dataViewModel.dataState.observe(viewLifecycleOwner, { ds ->
            when (ds) {
                is DataState.Success<List<Kontainer>> -> {
                    recyclerAdapter.setItems(ds.data)
                    recyclerAdapter.notifyDataSetChanged()
                    swiperLayout.isRefreshing = false
                    setContainerStats(ds.data)
                }
                is DataState.Error -> {
                    swiperLayout.isRefreshing = false
                    logout(mainActivity, "Issue with Portainer! Please login again.")
                }
                is DataState.Loading -> {
                    swiperLayout.isRefreshing = true
                    setContainerStats(listOf(), true)
                }
                /** below these is the logic for handling the idividual cards*/
                is DataState.CardLoading -> {
                    recyclerAdapter.setItems(ds.data)
                    recyclerAdapter.notifyItemChanged(ds.itemIndex)
                    setContainerStats(listOf(), true)
                }
                is DataState.CardSuccess -> {
                    swiperLayout.isRefreshing = false
                    recyclerAdapter.setItems(ds.data)
                    recyclerAdapter.notifyItemChanged(ds.itemIndex)
                    setContainerStats(ds.data)
                }
                is DataState.CardError -> {
                    recyclerAdapter.setItems(ds.data)
                    recyclerAdapter.notifyItemChanged(ds.itemIndex)
                    setContainerStats(ds.data)
                }
            }
        })
    }

    @ExperimentalCoroutinesApi
    private fun callGetContainers(
        dataViewModel: DockerListerViewModel,
        url: String,
        jwt: String,
        endpointId: Int,
    ) {
        val fullUrl =
            getString(R.string.getDockerContainers).replace("{baseUrl}", url.removeSuffix("/"))
                .replace("{endpointId}", endpointId.toString())

        dataViewModel.setStateEvent(MainStateEvent.GetKontejneri(jwt = jwt, url = fullUrl))
    }

    @ExperimentalCoroutinesApi
    fun callSwiperLogic(
        dataViewModel: DockerListerViewModel,
        globActivity: MainActiviy,
        globalVars: GlobalApp,
        recyclerAdapter: DockerContainerAdapter,
    ) {
        if (globActivity.isJwtValid()) {
            // don't refresh if there are any items that are transitioning between states
            if (recyclerAdapter.areItemsInTransitioningState())
                swiperLayout.isRefreshing = false
            else {
                callGetContainers(dataViewModel,
                    globalVars.currentUser!!.serverUrl,
                    globalVars.currentUser!!.jwt!!,
                    globalVars.currentUser!!.currentEndpoint.id)
            }
        } else {
            logout(globActivity, "Session has expired! Please log in again.")
        }
    }

    private fun setDrawerInfo(globalVars: GlobalApp) {
        // set the name of the logged in user and the server url
        tvLoggedUsername.text = globalVars.currentUser!!.username
        tvLoggedUrl.text = globalVars.currentUser!!.serverUrl

        //get version name of app
        val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        val appVersion: String = pInfo.versionName

        // use Markwon to format the text
        val markwon = Markwon.builder(requireContext())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeTextColor(ContextCompat.getColor(requireContext(), R.color.blue_main))
                        .linkColor(ContextCompat.getColor(requireContext(), R.color.teal_200))
                }
            }).usePlugin(LinkifyPlugin.create(Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS))
            .build()

        // get the text from the string resources and add the version number
        markwon.setMarkdown(tvAboutInfo, getString(R.string.about_app, appVersion))
    }

    private fun logout(mainActivity: MainActiviy, logoutMsg: String? = null) {
        mainActivity.invalidateJwt()
        mainActivity.setLogoutMsg(logoutMsg)

        val action =
            DockerListerFragmentDirections.actionDockerListerFragmentToHomeFragment()
        findNavController().navigate(action)
    }

    private fun setContainerStats(allContainers: List<Kontainer>, isLoading: Boolean = false) {
        fun showProgressBar(show: Boolean) {
            val pbAndTextVisibility = if (show)
                Pair(View.VISIBLE, View.INVISIBLE)
            else
                Pair(View.INVISIBLE, View.VISIBLE)

            pbTotalStats.visibility = pbAndTextVisibility.first
            pbRunningStats.visibility = pbAndTextVisibility.first
            pbStoppedStats.visibility = pbAndTextVisibility.first
            tvTotalStat.visibility = pbAndTextVisibility.second
            tvStoppedStat.visibility = pbAndTextVisibility.second
            tvRunningStat.visibility = pbAndTextVisibility.second
        }
        if (!isLoading) {
            tvTotalStat.text = allContainers.size.toString()
            tvRunningStat.text = allContainers.count { kon ->
                kon.state == ContainerStateType.RUNNING
            }.toString()
            tvStoppedStat.text = allContainers.count { kon ->
                kon.state == ContainerStateType.EXITED || kon.state == ContainerStateType.ERRORED
            }.toString()

            // if there are any more containers that are in transitioning state, don't show the stats, instead keep the spinner
            if (allContainers.any { it.state == ContainerStateType.TRANSITIONING })
                showProgressBar(true)
            else
                showProgressBar(false)
        } else {
            showProgressBar(true)
        }
    }

}