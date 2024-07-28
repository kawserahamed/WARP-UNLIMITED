package com.ahamed.warp.fragment

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.ahamed.warp.R
import com.ahamed.warp.databinding.FragmentHomeBinding
import com.ahamed.warp.databinding.LayoutInputIdBinding
import com.ahamed.warp.viewmodel.WarpViewModel

class HomeFragment : Fragment() {
    private val viewmodel: WarpViewModel by activityViewModels()
    private lateinit var binding: FragmentHomeBinding
    private var deviceID = ""
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentHomeBinding.inflate(inflater, container, false)

        binding.about.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_aboutFragment)
        }


        val inputBinding = LayoutInputIdBinding.inflate(layoutInflater, container, false)
        val dialogBuilder = AlertDialog.Builder(requireActivity()).setView(inputBinding.root)
        val alertDialog = dialogBuilder.create()
        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        binding.btnDeviceID.setOnClickListener {
            alertDialog.show()
        }
        inputBinding.btnClose.setOnClickListener {
            alertDialog.dismiss()
        }
        inputBinding.btnOkay.setOnClickListener {
            val strID = inputBinding.etId.editableText.toString()
            if (strID.isEmpty()) {
                inputBinding.etId.error = "Can't be empty"
                return@setOnClickListener
            }
            deviceID = strID
            binding.btnDeviceID.text = deviceID
            alertDialog.dismiss()
        }


        binding.btnStart.setOnClickListener {
            if (deviceID.isEmpty()) {
                Toast.makeText(requireActivity(), "ID can't be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewmodel.startRequestLoop(deviceID)
        }

        binding.btnStop.setOnClickListener {
            viewmodel.stopRequestLoop()
        }

        viewmodel.successCount.observe(viewLifecycleOwner) {
            binding.tvPass.text = "$it GB"
        }

        viewmodel.failCount.observe(viewLifecycleOwner) {
            binding.tvFail.text = "$it GB"
        }

        viewmodel.log.observe(viewLifecycleOwner) {
            binding.tvLog.text = it.toString()
        }

        viewmodel.timeDown.observe(viewLifecycleOwner) {
            startCountdownTimer(it)
        }

        viewmodel.isActive.observe(viewLifecycleOwner) {
            if (it) {
                binding.btnStart.visibility = View.GONE
                binding.btnStop.visibility = View.VISIBLE
            } else {
                binding.btnStart.visibility = View.VISIBLE
                binding.btnStop.visibility = View.GONE
            }
        }


        return binding.root
    }

    private fun startCountdownTimer(timeInMillis: Long) {
        val timer = object : CountDownTimer(timeInMillis, 100) {
            override fun onTick(millisUntilFinished: Long) {
                val progress =
                    (millisUntilFinished.toFloat() / timeInMillis.toFloat() * 100).toInt()

                binding.tvTime.text = "$progress%"
                binding.circularProgressBar.progress = 100 - progress
            }

            override fun onFinish() {
                binding.tvTime.text = "0%"
                binding.circularProgressBar.progress = 0
            }
        }
        timer.start()
    }

}