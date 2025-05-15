<template>
    <div class="upload-container">
        <label class="file-input-label">
            <input type="file" @change="handleFileSelect" class="file-input" hidden />
            <div class="custom-file-input">
                <svg class="upload-icon" viewBox="0 0 24 24">
                    <path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11z" />
                    <path d="M13 13h-2v4H9v2h6v-2h-2z" />
                </svg>
                <span>选择文件</span>
            </div>
        </label>

        <div class="button-group">
            <button @click="uploadFile" :disabled="!file || uploading" class="action-btn primary">
                <span v-if="uploading" class="loading"></span>
                {{ uploading ? '上传中...' : '开始上传' }}
            </button>

            <button @click="pauseUpload" :disabled="!uploading" class="action-btn secondary">
                暂停
            </button>
            <button 
        @click="simulateNetworkFailure" 
        :disabled="!uploading"
        class="action-btn warning"
      >
        模拟断网
      </button>
        </div>

        <div class="progress-container">
            <div class="progress-bar">
                <div class="progress-fill" :style="{ width: progress + '%' }"></div>
            </div>
            <div class="progress-text">当前进度：{{ progress }}%</div>
        </div>
    </div>
</template>

<script setup>
import { ref } from 'vue';
import axios from 'axios';
import SparkMD5 from 'spark-md5';

const CHUNK_SIZE = 1024 * 1024 * 50; // 1MB
let file = ref(null);
let fileHash = ref('');
let chunkList = ref([]);
let uploading = ref(false);
let progress = ref(0);
let cancelTokens = ref([]);

// 添加模拟网络中断方法
const simulateNetworkFailure = () => {
  // 取消所有进行中的请求
  cancelTokens.value.forEach(source => source.cancel());
  cancelTokens.value = [];
  
  // 模拟网络错误状态
  uploading.value = false;
  alert('网络连接已断开！');
  
  // 可选：自动恢复测试（根据需要添加）
  // setTimeout(() => {
  //   alert('网络已恢复，请重新上传');
  // }, 3000);
};

// 生成文件哈希
const calculateHash = async (file) => {
    return new Promise((resolve) => {
        const spark = new SparkMD5.ArrayBuffer();
        const reader = new FileReader();
        const size = file.size;
        const offset = 2 * 1024 * 1024;

        let chunks = [file.slice(0, offset)];
        let current = offset;

        reader.onload = (e) => {
            spark.append(e.target.result);
            if (current < size) {
                current += offset;
                chunks.push(file.slice(current, current + offset));
                reader.readAsArrayBuffer(chunks.pop());
            } else {
                resolve(spark.end());
            }
        };
        reader.readAsArrayBuffer(chunks.pop());
    });
};

// 文件选择处理
const handleFileSelect = async (e) => {
    const selectedFile = e.target.files[0];
    if (!selectedFile) return;

    file.value = selectedFile;
    fileHash.value = await calculateHash(selectedFile);

    // 初始化分块
    chunkList.value = Array.from(
        { length: Math.ceil(selectedFile.size / CHUNK_SIZE) },
        (_, i) => ({
            index: i,
            start: i * CHUNK_SIZE,
            end: (i + 1) * CHUNK_SIZE,
            uploaded: false
        })
    );
};

// 上传文件
const uploadFile = async () => {
    uploading.value = true;

    // 新增：捕获检查请求的404错误（新文件不存在记录的情况）
    let uploadedChunks = [];
    try {
        const { data } = await axios.get(
            `http://127.0.0.1:8080/api/upload/check?fileHash=${fileHash.value}`
        );
        uploadedChunks = data;
    } catch (error) {
        if (axios.isAxiosError(error) && error.response?.status === 404) {
            // 新文件直接上传全部分块
            uploadedChunks = [];
        } else {
            alert('检查分块失败');
            uploading.value = false;
            return;
        }
    }

    // 更新已上传分块状态
    chunkList.value = chunkList.value.map(chunk => ({
        ...chunk,
        uploaded: uploadedChunks.includes(chunk.index)
    }));

    // 上传未完成的分块
    const requests = chunkList.value
        .filter(chunk => !chunk.uploaded)
        .map(chunk => {
            const formData = new FormData();
            const blob = file.value.slice(chunk.start, chunk.end);

            formData.append('file', blob);
            formData.append('chunkIndex', chunk.index);
            formData.append('fileHash', fileHash.value);
            formData.append('chunkSize', CHUNK_SIZE);

            const cancelToken = axios.CancelToken.source();
            cancelTokens.value.push(cancelToken);

            return axios.post('http://127.0.0.1:8080/api/upload', formData, {
                cancelToken: cancelToken.token,
                onUploadProgress: (e) => {
                    const total = file.value.size;
                    const loaded = chunkList.value
                        .filter(c => c.uploaded)
                        .reduce((sum, c) => sum + (c.end - c.start), 0) + e.loaded;
                    progress.value = Math.round((loaded / total) * 100);
                }
            }).then(() => {
                chunk.uploaded = true;
            });
        });

    try {
        await Promise.all(requests);
        await axios.post('http://127.0.0.1:8080/api/upload/merge', {
            fileName: file.value.name,
            fileHash: fileHash.value,
            chunkSize: CHUNK_SIZE,
            fileSize: file.value.size
        });
        progress.value = 100;
        alert('Upload completed!');
    } catch (error) {
        if (!axios.isCancel(error)) {
            alert('Upload failed!');
        }
    } finally {
        uploading.value = false;
        cancelTokens.value = [];
    }
};

// 暂停上传
const pauseUpload = () => {
    cancelTokens.value.forEach(source => source.cancel());
    cancelTokens.value = [];
    uploading.value = false;
};
</script>

<style scoped>
.upload-container {
    max-width: 600px;
    margin: 2rem auto;
    padding: 2rem;
    background: #fff;
    border-radius: 12px;
    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}

.file-input-label {
    display: block;
    margin-bottom: 2rem;
    cursor: pointer;
}

.custom-file-input {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: 2rem;
    border: 2px dashed #e0e0e0;
    border-radius: 8px;
    transition: all 0.3s ease;
}

.custom-file-input:hover {
    border-color: #2196f3;
    background: rgba(33, 150, 243, 0.05);
}

.upload-icon {
    width: 48px;
    height: 48px;
    fill: #757575;
    margin-bottom: 1rem;
    transition: fill 0.3s ease;
}

.custom-file-input:hover .upload-icon {
    fill: #2196f3;
}

.button-group {
    display: flex;
    gap: 1rem;
    margin-bottom: 2rem;
}

.action-btn {
    flex: 1;
    padding: 12px 24px;
    border: none;
    border-radius: 6px;
    font-size: 1rem;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.3s ease;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
}

.action-btn.primary {
    background: linear-gradient(135deg, #2196f3, #1976d2);
    color: white;
}

.action-btn.primary:hover:not(:disabled) {
    transform: translateY(-2px);
    box-shadow: 0 4px 8px rgba(33, 150, 243, 0.3);
}

.action-btn.secondary {
    background: #f5f5f5;
    color: #424242;
}

.action-btn.secondary:hover:not(:disabled) {
    background: #eee;
}

.action-btn:disabled {
    opacity: 0.7;
    cursor: not-allowed;
}

.loading {
    width: 16px;
    height: 16px;
    border: 2px solid #fff;
    border-radius: 50%;
    border-top-color: transparent;
    animation: spin 0.8s linear infinite;
}

.progress-container {
    margin-top: 1.5rem;
}

.progress-bar {
    height: 12px;
    background: #eee;
    border-radius: 6px;
    overflow: hidden;
}

.progress-fill {
    height: 100%;
    background: linear-gradient(90deg, #4caf50, #8bc34a);
    transition: width 0.3s ease;
}

.progress-text {
    margin-top: 8px;
    font-size: 0.9rem;
    color: #616161;
    text-align: center;
}

@keyframes spin {
    to {
        transform: rotate(360deg);
    }
}

.action-btn.warning {
  background: linear-gradient(135deg, #ff9800, #f57c00);
  color: white;
}

.action-btn.warning:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 8px rgba(255, 152, 0, 0.3);
}
</style>