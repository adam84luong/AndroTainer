package com.dokeraj.androtainer

import android.os.Bundle
import android.text.util.Linkify
import android.view.View
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.dokeraj.androtainer.Interfaces.ApiInterface
import com.dokeraj.androtainer.buttons.BtnDeleteContainer
import com.dokeraj.androtainer.globalvars.GlobalApp
import com.dokeraj.androtainer.models.PContainer
import com.dokeraj.androtainer.models.logos.Logo
import com.dokeraj.androtainer.models.logos.Logos
import com.dokeraj.androtainer.network.RetrofitInstance
import com.google.gson.Gson
import com.squareup.picasso.Picasso
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.android.synthetic.main.fragment_docker_container_details.*
import kotlinx.android.synthetic.main.fragment_docker_lister.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.time.Instant.ofEpochSecond
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DockerContainerDetailsFragment : Fragment(R.layout.fragment_docker_container_details) {
    var isDeletingNow: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args: DockerContainerDetailsFragmentArgs by navArgs()

        val selectedContainer: PContainer = args.dContainer

        tbContainerDetails.navigationIcon =
            ContextCompat.getDrawable(requireActivity(), R.drawable.backlogo)

        val btnDeleteState: BtnDeleteContainer =
            BtnDeleteContainer(requireContext(), btnContainerDelete)

        tvContainerDetailsTitle.text = selectedContainer.name

        val globActivity: MainActiviy = (activity as MainActiviy?)!!
        val globalVars: GlobalApp = (globActivity.application as GlobalApp)

        setContainerDetails(selectedContainer)

        val allLogos: Logos =
            Gson().fromJson<Logos>(getString(R.string.allServicesLogos), Logos::class.java)

        val logoToDisplay: Logo? = allLogos.find { logo ->
            logo.names.any { lName ->
                lName.toLowerCase() in selectedContainer.pulledImage.toLowerCase()
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, true) {
            // Only allow to go back to docker lister only when we get any kind of response from the API (or timeout)
            if (!isDeletingNow)
                findNavController().popBackStack()
            else
                globActivity.showGenericSnack(requireContext(),
                    requireView(),
                    "Please wait until the ${selectedContainer.name} is deleted",
                    R.color.dis4,
                    R.color.blue_main)
        }

        logoToDisplay?.let {
            Picasso.get().load(it.url)
                .resize(it.width, it.height)
                .into(ivContainerLogo)
        }

        // on nav button back clicked set the global var that the swiperRefresh should be turned on
        tbContainerDetails.setNavigationOnClickListener {
            backToDockerLister(globActivity)
        }

        // show textview telling to do a long press in order to delete the container
        btnContainerDelete.setOnClickListener {
            val markwon = Markwon.builder(requireContext()).build()

            markwon.setMarkdown(tv_btn_remove_container_description,
                getString(R.string.deletingContainerNote).replace("{containerName}",
                    selectedContainer.name))

            tv_btn_remove_container_description.visibility = View.VISIBLE
        }

        // on long press - delete the docker container and go back to docker lister
        btnContainerDelete.setOnLongClickListener {
            isDeletingNow = true
            btnDeleteState.changeBtnState(false)
            tv_btn_remove_container_description.visibility = View.GONE

            deleteDockerContainer(selectedContainer,
                globalVars.currentUser!!.serverUrl,
                globalVars.currentUser!!.jwt!!,
                globActivity,
                btnDeleteState)

            true
        }
    }

    private fun backToDockerLister(globActivity: MainActiviy) {
        globActivity.setIsBackToDockerLister(true)
        requireActivity().onBackPressed()
    }

    private fun setContainerDetails(container: PContainer) {
        val markwon = Markwon.builder(requireContext())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeTextColor(ContextCompat.getColor(requireContext(), R.color.blue_main))
                        .linkColor(ContextCompat.getColor(requireContext(), R.color.teal_200))
                }
            })
            .usePlugin(LinkifyPlugin.create(Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS))
            .build()

        val id = "### ID\n- *${container.id}*\n"

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        val createdDate = formatter.format(ofEpochSecond(container.created))
        val formattedDate = "### Date Created\n- ${createdDate}\n"

        val imageName = "### Image\n- ${container.pulledImage}\n"

        val maintainer: String = container.maintainerInfo.maintainer?.let {
            val maintainerUrl: String =
                container.maintainerInfo.url?.let { url -> "- url: ${url}\n" } ?: ""
            "### Maintainer Info\n- name: ${container.maintainerInfo.maintainer}\n${maintainerUrl}"
        } ?: ""

        val hostConfig = "### Host Config\n- Network Mode: `${container.hostConfig.networkMode}`\n"

        val allMounts: String =
            if (container.mounts.isNotEmpty() && container.mounts.any { m -> m.type == "bind" }) {
                val mount = "### Mounts [*External* : *Internal*]\n"
                val mounts: String = container.mounts.filter { m -> m.type == "bind" }.map { m ->
                    "- `${m.source}`:`${m.destination}`\n"
                }.joinToString("\n")

                "$mount$mounts"
            } else
                ""

        val allPorts: String =
            if (container.ports.isNotEmpty()) {
                val port = "### Ports [*PublicPort* : *PrivatePort* - protocol]\n"
                val ports: String = container.ports.map { p ->
                    "> `${p.publicPort.let { it } ?: "none"}`:`${p.privatePort}` - **${p.type}**\n"
                }.joinToString("\n")

                "$port$ports"
            } else
                ""

        val state = "### State\n- ***${container.state.name.toLowerCase().capitalize()}***\n"

        val status = "### Status\n- ***${container.status.toLowerCase().capitalize()}***\n"

        val completeInfo =
            StringBuilder().append(id).append(formattedDate).append(imageName).append(maintainer)
                .append(hostConfig).append(allMounts).append(allPorts).append(state).append(status)
        markwon.setMarkdown(tvContainerDetailsInfo,
            "${completeInfo}")

    }

    private fun deleteDockerContainer(
        selectedContainer: PContainer,
        baseUrl: String,
        jwt: String,
        globActivity: MainActiviy,
        btnDeleteState: BtnDeleteContainer,
    ) {
        val fullUrl =
            getString(R.string.removeDockerContainer).replace("{baseUrl}",
                baseUrl.removeSuffix("/"))
                .replace("{containerId}", selectedContainer.id)
        val header = "Bearer $jwt"

        val api = RetrofitInstance.retrofitInstance!!.create(ApiInterface::class.java)
        api.removeDockerContainer(header, fullUrl, true, 1)
            .enqueue(object : Callback<Unit?> {
                override fun onResponse(call: Call<Unit?>, response: Response<Unit?>) {
                    when (response.code()) {
                        204 -> {
                            isDeletingNow = false
                            backToDockerLister(globActivity)
                        }
                        else -> {
                            isDeletingNow = false
                            btnDeleteState.changeBtnState(true)

                            globActivity.showGenericSnack(requireContext(),
                                requireView(),
                                "Error deleting container ${selectedContainer.name}! Please try again.",
                                R.color.white,
                                R.color.orange_warning)
                        }
                    }
                }

                override fun onFailure(call: Call<Unit?>, t: Throwable) {
                    isDeletingNow = false
                    btnDeleteState.changeBtnState(true)
                    globActivity.showGenericSnack(requireContext(),
                        requireView(),
                        "Error deleting container ${selectedContainer.name}! Please logout and login again.",
                        R.color.white,
                        R.color.disRed)
                }
            })
    }


}