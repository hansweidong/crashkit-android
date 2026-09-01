#pragma once

#include <cstddef>

void crashkit_set_fd_hook(int type, bool inline_mode, bool java_stack);
void crashkit_set_mem_hook(bool enable);
void crashkit_set_thread_hook(bool enable);
void crashkit_format_fd_hook(char* out, size_t cap);
void crashkit_format_mem_hook(char* out, size_t cap);
void crashkit_format_native_stack(char* out, size_t cap);
