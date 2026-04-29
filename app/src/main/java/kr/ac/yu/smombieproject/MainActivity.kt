package kr.ac.yu.smombieproject

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import kr.ac.yu.smombieproject.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ViewBinding 초기화
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 네이티브 엔진 검증 결과 출력
        try {
            val status = checkOpenCV()
            binding.sampleText.text = status

            // 성공 시 초록색 계열로 텍스트 색상 변경 (가시성)
            binding.sampleText.setTextColor(0xFF4CAF50.toInt())
        } catch (e: Exception) {
            binding.sampleText.text = "Native Library Load Failed"
            Toast.makeText(this, "NDK 연결 오류 발생", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * C++ 라이브러리 로드
     * CMake의 project("smombieproject") 이름과 일치해야 함
     */
    companion object {
        init {
            System.loadLibrary("smombieproject")
        }
    }

    // 네이티브 함수 선언
    external fun checkOpenCV(): String
}