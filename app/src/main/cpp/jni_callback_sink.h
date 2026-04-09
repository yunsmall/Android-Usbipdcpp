#pragma once

#include <spdlog/sinks/base_sink.h>
#include <spdlog/spdlog.h>
#include <functional>
#include <mutex>
#include <string>

// 自定义sink，通过JNI回调把日志传到Kotlin
template<typename Mutex>
class jni_callback_sink : public spdlog::sinks::base_sink<Mutex> {
public:
    using callback_fn = std::function<void(spdlog::level::level_enum, const std::string&)>;

    explicit jni_callback_sink(callback_fn callback) : callback_(std::move(callback)) {}

protected:
    void sink_it_(const spdlog::details::log_msg& msg) override {
        if (!callback_) return;

        spdlog::memory_buf_t formatted;
        this->formatter_->format(msg, formatted);
        std::string log_str = fmt::to_string(formatted);

        callback_(msg.level, log_str);
    }

    void flush_() override {}

private:
    callback_fn callback_;
};

using jni_callback_sink_mt = jni_callback_sink<std::mutex>;