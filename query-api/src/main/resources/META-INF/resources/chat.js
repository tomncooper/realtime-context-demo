// SmartShip Chat - Inline Chat Card
const CHAT_API_ENDPOINT = '/api/chat';
const CHAT_HEALTH_ENDPOINT = '/api/chat/health';
const SESSION_KEY = 'smartship_session_id';

// State variables
let messageCounter = 0;
let llmStatus = 'unknown';
let thinkingStartTime = null;
let thinkingTimerInterval = null;

// Get or create session ID
function getSessionId() {
    let sessionId = localStorage.getItem(SESSION_KEY);
    if (!sessionId) {
        sessionId = crypto.randomUUID();
        localStorage.setItem(SESSION_KEY, sessionId);
    }
    return sessionId;
}

// Start the thinking timer
function startThinkingTimer() {
    thinkingStartTime = Date.now();
    updateThinkingMessage();
    thinkingTimerInterval = setInterval(updateThinkingMessage, 100);
}

// Stop the thinking timer
function stopThinkingTimer() {
    if (thinkingTimerInterval) {
        clearInterval(thinkingTimerInterval);
        thinkingTimerInterval = null;
    }
    thinkingStartTime = null;
}

// Update the thinking message with elapsed time
function updateThinkingMessage() {
    const loadingMessage = document.querySelector('.chat-message.loading');
    if (loadingMessage && thinkingStartTime) {
        const elapsed = ((Date.now() - thinkingStartTime) / 1000).toFixed(1);
        loadingMessage.textContent = `Thinking... (${elapsed}s)`;
    }
}

// Check LLM health status
async function checkLlmHealth() {
    const indicator = document.getElementById('llm-status-indicator');
    const statusText = document.getElementById('llm-status-text');

    if (!indicator || !statusText) return null;

    try {
        const response = await fetch(CHAT_HEALTH_ENDPOINT);
        const data = await response.json();

        llmStatus = data.llm_status || 'unknown';
        updateLlmStatusIndicator(llmStatus, data);

        return data;
    } catch (error) {
        console.warn('LLM health check failed:', error);
        llmStatus = 'error';
        updateLlmStatusIndicator('error', { error: error.message });
        return null;
    }
}

// Update LLM status indicator in the UI
function updateLlmStatusIndicator(status, data) {
    const indicator = document.getElementById('llm-status-indicator');
    const statusText = document.getElementById('llm-status-text');

    if (!indicator || !statusText) return;

    // Remove all status classes
    indicator.classList.remove('status-up', 'status-down', 'status-degraded', 'status-unknown');

    switch ((status || '').toUpperCase()) {
        case 'UP':
        case 'CONFIGURED':
            indicator.classList.add('status-up');
            statusText.textContent = data.model ? `AI: ${data.model}` : 'AI: Ready';
            break;
        case 'DEGRADED':
            indicator.classList.add('status-degraded');
            statusText.textContent = 'AI: Degraded';
            break;
        case 'DOWN':
        case 'ERROR':
            indicator.classList.add('status-down');
            statusText.textContent = 'AI: Offline';
            break;
        default:
            indicator.classList.add('status-unknown');
            statusText.textContent = 'AI: Checking...';
    }
}

// Initialize chat
document.addEventListener('DOMContentLoaded', () => {
    const chatInput = document.getElementById('chat-input');
    const chatSendBtn = document.getElementById('chat-send');
    const suggestionChips = document.querySelectorAll('.suggestion-chip');
    const llmCheckBtn = document.getElementById('llm-check-btn');

    // Initial LLM health check
    checkLlmHealth();

    // Periodic health check (every 30 seconds, aligned with dashboard refresh)
    setInterval(checkLlmHealth, 30000);

    // Manual health check button
    if (llmCheckBtn) {
        llmCheckBtn.addEventListener('click', () => {
            checkLlmHealth();
        });
    }

    // Send on button click
    chatSendBtn.addEventListener('click', () => {
        sendChatMessage(chatInput.value);
    });

    // Send on Enter key
    chatInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            sendChatMessage(chatInput.value);
        }
    });

    // Suggestion chip clicks
    suggestionChips.forEach(chip => {
        chip.addEventListener('click', () => {
            const query = chip.dataset.query;
            sendChatMessage(query);
        });
    });
});

// Send chat message to API
async function sendChatMessage(message) {
    if (!message || !message.trim()) return;

    const chatInput = document.getElementById('chat-input');

    // Check LLM status before sending
    if (llmStatus === 'DOWN' || llmStatus === 'error') {
        addMessage('The AI assistant is currently unavailable. Please try again later.', 'assistant error');
        return;
    }

    // Clear input
    chatInput.value = '';

    // Add user message to chat
    addMessage(message, 'user');

    // Show loading with timer
    const loadingId = addMessage('Thinking...', 'assistant loading');
    startThinkingTimer();

    try {
        const response = await fetch(CHAT_API_ENDPOINT, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json'
            },
            body: JSON.stringify({
                message: message,
                sessionId: getSessionId()
            })
        });

        // Stop timer and remove loading message
        stopThinkingTimer();
        removeMessage(loadingId);

        if (!response.ok) {
            if (response.status === 503) {
                throw new Error('LLM service unavailable');
            }
            throw new Error(`HTTP ${response.status}`);
        }

        const data = await response.json();
        const formattedResponse = formatResponse(data.response);
        addMessage(formattedResponse, 'assistant', true);

    } catch (error) {
        stopThinkingTimer();
        removeMessage(loadingId);

        let errorMsg = 'Sorry, I encountered an error. ';
        if (error.message.includes('unavailable') || error.message.includes('503')) {
            errorMsg += 'The AI service is temporarily unavailable.';
            // Trigger health recheck
            checkLlmHealth();
        } else if (error.message.includes('timeout') || error.message.includes('Failed to fetch')) {
            errorMsg += 'The request timed out. Please try a simpler question.';
        } else {
            errorMsg += 'Please try again.';
        }

        addMessage(errorMsg, 'assistant error');
        console.error('Chat error:', error);
    }
}

// Add message to chat container
function addMessage(content, type, isHtml = false) {
    const container = document.getElementById('chat-messages');
    const messageDiv = document.createElement('div');
    const messageId = 'msg-' + Date.now() + '-' + (messageCounter++);

    messageDiv.id = messageId;
    messageDiv.className = `chat-message ${type}`;

    if (isHtml) {
        messageDiv.innerHTML = content;
    } else {
        messageDiv.textContent = content;
    }

    container.appendChild(messageDiv);

    // Scroll to bottom
    container.scrollTop = container.scrollHeight;

    return messageId;
}

// Remove message by ID
function removeMessage(messageId) {
    const message = document.getElementById(messageId);
    if (message) {
        message.remove();
    }
}

// Format response with markdown-like styling
function formatResponse(text) {
    // Inline styles for lists (can't use external CSS for all contexts)
    const ulStyle = 'style="margin: 0.5rem 0; padding-left: 1.25rem; list-style-type: disc;"';
    const subUlStyle = 'style="margin: 0.25rem 0; padding-left: 1.25rem; list-style-type: circle;"';
    const liStyle = 'style="margin: 0.25rem 0;"';

    // Convert markdown-style formatting to HTML
    let html = text
        // Bold text: **text** -> <strong>text</strong>
        .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');

    // Process lines for list formatting
    const lines = html.split('\n');
    let result = [];
    let inList = false;
    let inSubList = false;

    for (let i = 0; i < lines.length; i++) {
        let line = lines[i];

        // Check for sub-list items (+ at start)
        if (line.match(/^\s*\+\s+/)) {
            if (!inSubList) {
                if (!inList) {
                    result.push(`<ul ${ulStyle}>`);
                    inList = true;
                }
                result.push(`<ul ${subUlStyle}>`);
                inSubList = true;
            }
            line = `<li ${liStyle}>` + line.replace(/^\s*\+\s+/, '') + '</li>';
            result.push(line);
        }
        // Check for main list items (* at start)
        else if (line.match(/^\s*\*\s+/)) {
            if (inSubList) {
                result.push('</ul>');
                inSubList = false;
            }
            if (!inList) {
                result.push(`<ul ${ulStyle}>`);
                inList = true;
            }
            line = `<li ${liStyle}>` + line.replace(/^\s*\*\s+/, '') + '</li>';
            result.push(line);
        }
        // Regular line
        else {
            if (inSubList) {
                result.push('</ul>');
                inSubList = false;
            }
            if (inList) {
                result.push('</ul>');
                inList = false;
            }
            // Convert newlines to <br> for non-list content
            if (line.trim()) {
                result.push(line);
            } else if (result.length > 0) {
                result.push('<br>');
            }
        }
    }

    // Close any open lists
    if (inSubList) {
        result.push('</ul>');
    }
    if (inList) {
        result.push('</ul>');
    }

    return result.join('\n');
}
