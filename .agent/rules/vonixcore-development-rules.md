# VonixCore Development Rules

## Git Workflow Rules

### Commit Message Format
All commits must follow conventional commit format:
- `feat:` for new features
- `fix:` for bug fixes  
- `refactor:` for code refactoring
- `docs:` for documentation changes
- `chore:` for maintenance tasks

### Pre-push Checklist
Before pushing to main branch:
1. Ensure all changes compile successfully
2. Run tests if available
3. Verify version consistency across templates
4. Check mixin configurations are correct for target platform
5. Update gradle.properties with correct dependency versions

### Version Management
- Always keep version numbers consistent between 1.20.1 and 1.21.1 templates
- Update VERSION in VonixCore.java when making template changes
- Verify Minecraft version compatibility in gradle.properties

### Template Porting Rules
When porting between Minecraft versions:
1. Maintain identical feature sets across templates
2. Update API calls for new Minecraft versions (e.g., Advancement → AdvancementHolder)
3. Adjust mixin compatibility levels (JAVA_17 → JAVA_21)
4. Update dependency versions in gradle.properties
5. Verify platform-specific configurations (Forge → NeoForge)

### Code Quality Standards
- Use VonixCore.executeAsync() for async operations
- Maintain consistent error handling patterns
- Add debug logging for critical functionality
- Keep mixin implementations minimal and focused

## Repository Structure Rules
- Keep common module identical across all templates
- Platform-specific code goes in respective module directories
- Mixin configurations must match platform requirements
- Shadow jar configurations must prevent conflicts
