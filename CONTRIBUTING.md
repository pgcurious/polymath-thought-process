# Contributing to "The Architecture of Attention"

Thank you for your interest in improving this book! This is a living document that grows through community contributions.

## Ways to Contribute

### 1. **Report Errors**
Found a typo, incorrect calculation, or outdated information?
- Open an issue with the chapter and section
- Describe what's wrong and what it should be
- Include references if applicable

### 2. **Suggest Improvements**
Have ideas for better explanations or additional content?
- Open an issue describing your suggestion
- Explain why it would improve the book
- Be specific about where it fits

### 3. **Add Examples**
Real-world examples make concepts concrete:
- Open a pull request with your example
- Include context (company size, scale, outcomes)
- Explain what readers can learn from it

### 4. **Contribute Code**
Improve or add code examples:
- Ensure code compiles and runs
- Include comments explaining the approach
- Add usage examples in main() method
- Follow existing code style

### 5. **Create Diagrams**
Visual learners appreciate diagrams:
- Use Mermaid syntax for consistency
- Keep diagrams focused on one concept
- Include explanatory text

### 6. **Extend Exercises**
Help readers practice:
- Add new thought experiments
- Create interview questions
- Provide detailed solution approaches

## Contribution Guidelines

### Style

**Writing**:
- Use active voice ("The system processes" not "The system is processed")
- Explain WHY before HOW
- Use concrete examples before abstractions
- Define terms before using them

**Code**:
- Prioritize clarity over cleverness
- Include inline comments for complex logic
- Provide realistic examples
- Show both naive and optimized versions

**Tone**:
- Conversational but precise
- Honest about trade-offs
- Respectful of different approaches
- Avoid dogma ("must" vs. "consider")

### Technical Accuracy

- Cite sources for statistics and claims
- Show calculations step-by-step
- Acknowledge limitations of approaches
- Update for new technologies when relevant

### Scope

**In scope**:
- Notification system design and implementation
- Distributed systems patterns
- Scale considerations
- Real-world trade-offs
- Ethical implications

**Out of scope**:
- Specific framework tutorials (Spring Boot, Express.js)
- Language-specific features (unless illustrating a concept)
- Marketing/growth tactics
- Unrelated system designs

## Pull Request Process

1. **Fork the repository**

2. **Create a feature branch**
   ```bash
   git checkout -b add-circuit-breaker-example
   ```

3. **Make your changes**
   - Follow style guidelines
   - Test code examples
   - Check spelling and grammar

4. **Write a clear commit message**
   ```
   Add circuit breaker example with APNs integration

   - Demonstrates half-open state testing
   - Shows metrics integration
   - Includes runnable example
   ```

5. **Submit a pull request**
   - Reference any related issues
   - Explain what changed and why
   - Describe how you tested it

6. **Respond to feedback**
   - Reviews may suggest improvements
   - Discussion helps refine ideas
   - Collaboration makes the book better

## Code Example Standards

All code examples should:

✅ **Compile and run without errors**

✅ **Include complete imports**

✅ **Demonstrate the concept clearly**

✅ **Show realistic usage**

✅ **Handle errors appropriately**

✅ **Include explanatory comments**

✅ **Have a main() method with example usage**

Example:

```java
/**
 * Token Bucket Rate Limiter
 *
 * Demonstrates rate limiting with burst capacity.
 * Used in Chapter 4: The Write Path
 */
public class RateLimiter {
    // Clear implementation...

    /**
     * Example usage
     */
    public static void main(String[] args) {
        // Realistic example...
    }
}
```

## Chapter Structure

When adding content to chapters, follow this structure:

1. **Hook**: Start with a relatable scenario or question
2. **Problem**: Explain what makes this hard
3. **Thought experiment**: Engage the reader actively
4. **Naive solution**: Show the obvious approach and why it fails
5. **Evolution**: Show 2-3 iterations of improvement
6. **Final solution**: Present the production-ready approach
7. **Trade-offs**: Discuss honestly what you're giving up
8. **Polymath's corner**: Connect to other domains (optional)
9. **Interview question**: Turn concept into practical question

## Topics We're Looking For

### High Priority

- [ ] More real-world production examples
- [ ] Additional edge cases from experience
- [ ] Performance benchmarks with real numbers
- [ ] Alternative architectural approaches
- [ ] Updated best practices for new technologies

### Medium Priority

- [ ] Additional diagrams for complex flows
- [ ] More code examples in other languages (Go, Python)
- [ ] Expanded exercises with solutions
- [ ] Case studies from different industries
- [ ] Troubleshooting guides

### Nice to Have

- [ ] Video walkthroughs of key concepts
- [ ] Interactive demos
- [ ] Conference talk references
- [ ] Podcast episode discussions

## Getting Help

Questions about contributing?

- **Open a discussion** for general questions
- **Open an issue** for specific suggestions
- **Tag @maintainers** for guidance

## Recognition

Contributors will be:
- Listed in the acknowledgments section
- Credited in commit history
- Recognized in release notes

Significant contributions may warrant co-authorship credit.

## Code of Conduct

### Our Standards

**Be respectful**:
- Value different perspectives
- Assume good intent
- Provide constructive feedback
- Acknowledge mistakes gracefully

**Be collaborative**:
- Share knowledge generously
- Help others learn
- Celebrate contributions
- Build each other up

**Be rigorous**:
- Back claims with evidence
- Test your code
- Proofread your writing
- Strive for accuracy

### Unacceptable Behavior

- Dismissive or condescending language
- Personal attacks or insults
- Harassment of any kind
- Publishing others' private information
- Promoting harmful technologies without ethical discussion

## License

By contributing, you agree that your contributions will be licensed under the same [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) license as the rest of the book.

## Questions?

Not sure if your contribution fits? **Ask first!** Open an issue describing what you want to contribute, and we'll provide guidance.

---

**Thank you for helping make "The Architecture of Attention" better!**

Your contributions help engineers around the world build better systems and make more ethical choices.
