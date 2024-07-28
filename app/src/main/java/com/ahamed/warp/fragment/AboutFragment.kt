package com.ahamed.warp.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.ahamed.warp.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val binding = FragmentAboutBinding.inflate(inflater, container, false)

        binding.btnBack.setOnClickListener {
            findNavController().popBackStack()
        }
        binding.btnFB.setOnClickListener {
            openFacebookProfile("kawserrrrr")
        }
        binding.btnGit.setOnClickListener { openGitHubProfile("kawserahamed") }
        binding.btnLin.setOnClickListener { openLinkedInProfile("kawserahamed") }




        return binding.root
    }

    private fun openFacebookProfile(profileId: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse("fb://profile/$profileId")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            intent.data = Uri.parse("https://www.facebook.com/$profileId")
            startActivity(intent)
        }
    }

    private fun openLinkedInProfile(profileId: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse("linkedin://in/$profileId")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            intent.data = Uri.parse("https://www.linkedin.com/in/$profileId")
            startActivity(intent)
        }
    }

    private fun openGitHubProfile(profileId: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse("github://user/$profileId")
        try {
            startActivity(intent)
        } catch (e: Exception) {
            intent.data = Uri.parse("https://github.com/$profileId")
            startActivity(intent)
        }
    }

}